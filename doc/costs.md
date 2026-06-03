# Cost tracking, AI Usage, manual overrides

Every billable LLM / rerank call is costed from its token usage ×
the resolved per-model price. The same machinery feeds four
surfaces: the global **Spend & usage** dashboard, the per-report
**Costs** breakdown, the **Model Info** per-model usage card, and
the per-report **API-call cost ledger** that drives all three.

## How a call is costed

`PricingCache.computeInOutCost(usage, pricing)`
(`data/PricingCache.kt:275`) turns a `TokenUsage` + a resolved
`ModelPricing` into `(inDollars, outDollars)`:

- Base: `inputTokens × promptPrice + outputTokens × completionPrice`.
- Cached input is billed at `cachedReadPrice` (falls back to
  `promptPrice`); Anthropic cache-creation tokens at
  `cachedWritePrice`.
- **Above-200k tier** — when `inputTokens + cachedInputTokens +
  cacheCreationTokens > 200 000` and `promptPriceAbove200k` is set,
  the whole call switches to the high-context rates (Gemini 2.5/3
  Pro, DashScope Qwen-Long, legacy Anthropic Sonnet 4, …). Each
  `*Above200k` field falls back to its base sibling when null.
- **`apiCost` shortcut** — if the provider returns a total, it is
  split pro-rata across input/output by the simple-rate baseline so
  callers still get two halves. When the baseline rates are all zero
  (free / `DEFAULT` pricing) but the API still shipped a total, the
  split falls back to the token ratio rather than dumping 100 % on
  output (Bug 38).
- **Rerank** bills per search-unit, not per token:
  `searchUnits × perQueryPrice` (Cohere `$2 / 1000 searches` →
  `0.002` per unit, read from LiteLLM's `input_cost_per_query`). The
  token columns are zero by design.

`computeCost(usage, pricing)` (`data/PricingCache.kt:263`) is the
single-number sibling: it returns `usage.apiCost` verbatim when the
provider reported one, otherwise `inCost + outCost` from
`computeInOutCost`.

`TokenUsage` (`data/AnalysisRepository.kt:30`) carries `inputTokens`,
`outputTokens`, `cachedInputTokens`, `cacheCreationTokens`,
`reasoningTokens` (all default 0) and the optional provider-reported
`apiCost: Double?`. `ModelPricing` (`data/PricingCache.kt:113`)
carries every rate field (per-**token**, not per-million) plus the
above-200k tier, the cache rates, `perQueryPrice`, and a `source`
string naming the tier that answered.

## Resolved pricing — layered lookup

`PricingCache.getPricing(context, provider, model)`
(`data/PricingCache.kt:369`) walks tiers top→bottom, first hit wins:

| # | Tier | `source` | Note |
|---|---|---|---|
| 1 | OpenRouter self | `OPENROUTER-SELF` | only when caller's provider has `crossProviderModelList` (OpenRouter) |
| 2 | Together self | `TOGETHER-SELF` | only when caller's provider has `pricingFromModelList` (Together) |
| 3 | **Manual override** | `OVERRIDE` | user-set, keyed `"${provider.id}:$model"` |
| 4 | LiteLLM | `LITELLM` | curated bulk |
| 5 | models.dev | `MODELSDEV` | curated bulk |
| 6 | llm-prices | `LLMPRICES` | curated bulk |
| 7 | Artificial Analysis | `AA` | curated bulk |
| 8 | OpenRouter cross-provider | `OPENROUTER` | only for non-OpenRouter callers |
| 9 | Helicone | `HELICONE` | last resort (known data-quality issues) |
| 10 | DEFAULT | `DEFAULT` | `ModelPricing("default", 25e-6, 75e-6)` = **$25/M in, $75/M out** |

Two precedence facts are easy to get wrong (the class-level KDoc at
`PricingCache.kt:14-21` still describes a stale "five-tier API >
LITELLM > OVERRIDE > OPENROUTER > DEFAULT" model — trust the code at
lines 369-398, not that comment):

- The **manual override sits before every curated tier** (step 3,
  ahead of LiteLLM). A user adding an override specifically to
  correct a stale catalog entry must win, or the Cost Config screen
  would silently ignore it.
- But the **two provider-self-report tiers still beat the override**
  (steps 1-2): when the caller *is* OpenRouter or Together, that
  provider's own `/v1/models` pricing block is the authoritative
  billing rate.

`DEFAULT` is **not** free — it is a deliberately high `$25/M in,
$75/M out` so an uncatalogued model surfaces as an obvious
over-estimate rather than reading $0 (which would hide a real spend).

Variants of the same lookup:

- `getPricingWithoutOverride` (`data/PricingCache.kt:408`) mirrors the
  precedence minus step 3 — it answers "what would the layered price
  be without your override?" for the override form's *Current:* line
  and for cleanup.
- `lookupPricing(provider, model)` (`data/PricingCache.kt:437`) is the
  context-free, never-blocking in-memory variant. It mirrors
  `getPricing` exactly (including OVERRIDE-before-LiteLLM) but never
  touches disk; returns `DEFAULT_PRICING` if the catalogs are not yet
  loaded. **This is the variant the usage-stats chokepoint uses** (see
  below), so cost snapshots never block a token-usage event on a
  disk read.

The seven external sources are documented in
[repositories.md](repositories.md).

On the main thread before preload completes, `getPricing` returns
`DEFAULT_PRICING` (and `ensureLoaded` short-circuits) so the UI never
blocks on the 1.2 MB catalog parse; recomposition picks up real
values once the off-thread preload finishes. Don't try to remove that
guard.

## Usage statistics store — the single chokepoint

Every token-usage event funnels through
`SettingsPreferences.updateUsageStats(provider, model, usage, kind,
searchUnits)` (`ui/settings/SettingsPreferences.kt:619`). It does four
things in one pass:

1. **Live dashboard** — feeds `ApiUsageRates.record` for the rolling
   5-minute spend/token rate.
2. **Cost snapshot** — `computeUsageCostSnapshot`
   (`ui/settings/SettingsPreferences.kt:589`) prices the call via
   `PricingCache.lookupPricing` + `computeInOutCost` +
   `searchUnits × perQueryPrice`. The `pricingSource` is forced to
   `"API_REPORTED"` when the provider sets `extractApiCost` or
   `costTicksDivisor` (OpenRouter / Perplexity / xAI), otherwise it is
   the resolved tier's `source`.
3. **Three in-memory stores** (`ConcurrentHashMap`), each keyed
   differently:
   - `UsageStats` keyed **`"${provider.id}::$model::$category"`**,
   - `UsageCategoryStats` keyed by category,
   - `UsageReportStats` keyed by `reportId`.
4. **Per-report ledger** — appends a `ReportApiCallCost` row to the
   report (see the ledger section).

The **`category`** in the `UsageStats` key is not the bare `kind`
argument: it is
`normalizeUsageKind(ApiTracer.currentCategory ?: normalizeUsageKind(kind))`
(`SettingsPreferences.kt:626`). So a primary report agent call lands
under its trace category (`report/prompt`), a rerank under
`after/rerank`, a meta under `meta/<promptName>`, a translation under
`translate/...`, and so on. `normalizeUsageKind`
(`data/ApiCallKinds.kt:28`) maps the legacy sweep labels (Temperature
sweep → `model/temperature`, etc.) and defaults a `null` kind to
`"report"`. The kinds passed explicitly by the metadata/secondary
engines are `report` (default), `rerank`, `moderation`, `meta`,
`translate`, `tournament`, `judges`, `compare`, `icon`, `title`,
`language`.

`UsageStats` (`model/SettingsModels.kt:1039`) holds `callCount`,
`inputTokens` / `outputTokens` (`Long`), `searchUnits`, `kind`, and
the persisted `inputCost?` / `outputCost?` / `pricingSource?` frozen at
call time (legacy rows written before cost-caching have null costs and
fall back to a live `PricingCache` lookup on read). Its `key` getter is
`"${provider.id}::$model::$kind"`. Rows are flushed to
`<filesDir>/usage-stats.json` debounced once per `USAGE_STATS_FLUSH_MS`
(2 s); the category and report variants live in
`usage-category-stats.json` / `usage-report-stats.json`. See
[persistent.md](persistent.md).

Legacy rows written before `kind` existed deserialize via Gson's
`Unsafe` path with a runtime-null `kind`; `ensureUsageStatsCache`
(`SettingsPreferences.kt:439`) backfills them to `"report"` so the
non-null contract holds. Parse is per-row, so a single unresolvable
provider id (a deleted custom provider) doesn't drop the whole file;
if *every* row fails (ProviderRegistry not yet initialised) the cache
is left null so the next read retries.

`clearUsageStats` (`SettingsPreferences.kt:905`) clears all three
caches, resets the flush timestamp, and deletes the three JSON files.

## Per-report API-call cost ledger

Each costed call also appends a `ReportApiCallCost`
(`data/ReportModels.kt:160`) row to the owning report's
`apiCallCosts` list when `ApiTracer.currentReportId` is set —
`type` (the `<category>/<prompt>` string), `provider`, `model`,
`pricingTier`, in/out tokens, in/out cost, `searchUnits`,
`durationMs`, `traceFile`. `API_CALL_COST_LEDGER_VERSION = 3`
(`data/ReportStorage.kt:35`); a report whose `apiCallCostsComplete`
flag is set and whose `apiCallCostsVersion >= 3` is treated as a
complete ledger (`isApiCallCostLedgerCurrent`,
`data/ReportStorage.kt:1304`).

Two correctness guards live here:

- `appendApiCallCost` (`data/ReportStorage.kt:1273`) returns **null on
  a dedup hit** (`record.id` already present). The caller
  `recordReportApiCallCost` treats a non-null return as "a row was
  added" and bumps the per-report usage stats; returning null on a
  retry/replay with a stable id stops it inflating
  `UsageReportStats` with no matching ledger row.
- `removeAgent` rolls deleted-agent icon spend into
  `Report.costsFromDeletedItems` as
  `maxOf(agentIconCost, structuredIconCallCost)` — some
  secondary-attributed `alt/report` icon-call rows leave the agent's
  `iconInputCost`/`iconOutputCost` at $0, so taking the max preserves
  the real spend without double-counting when both agree.

## Per-report total

`ReportStorage.computeReportTotalCost(report)`
(`data/ReportStorage.kt:122`) sums agent primary cost + per-agent icon
+ per-agent model-title + report-level icon/title/titleLong/language/
languageIcon costs + the Find-alt title fan-out `iconCalls` of
`TITLE_ALT_TYPES = {"alt/report_title","alt/report_title_long",
"alt/model_title"}` whose `attributedToSecondaryId == null` (the one
alt category with no structured cost home). `costsFromDeletedItems` is
tracked separately and intentionally **excluded** from this number —
the Manage bottom bar and HTML export add it back in, the View Costs
screen does not (it shows "Current items total").

## Spend & usage dashboard

Reached from the **Statistics** screen (`AiStatisticsScreen`,
`ui/admin/AiDashboardScreen.kt`, help `ai_statistics`) as the 💰
**Spend & usage** link-card.
The screen is `AiSpendUsageScreen`
(`ui/admin/AiDashboardScreen.kt:1174`, help `ai_spend_usage`). On open
it does a one-time OpenRouter pricing refresh (when stale) then
computes its breakdown via `computeUsageGroups`
(`data/DashboardStats.kt:157`) off the main thread — heavy because it
calls `getPricing` per model. Rerank rows fold
`searchUnits × perQueryPrice` into their cost.

A **Total card** (calls / tokens / cost, green) sits above a
four-tab body (`SpendUsageMode`: **Providers / Types / Reports /
Models**):

- **Providers** — one row per provider (Provider / Calls / Tokens /
  Cost / 🐞), sortable by any column header (default cost,
  descending). The 🐞 opens API Traces scoped to that provider and
  shows only when that provider has a captured trace. Tapping a row
  opens **`AiSpendUsageProviderScreen`**
  (`AiDashboardScreen.kt:1661`, help `ai_usage_provider`) — the
  per-provider breakdown grouped by call kind, by pricing source
  (OVERRIDE / OPENROUTER / LITELLM / …), and by model; each model row
  links to that model's **Model Info** page.
- **Types** — one row per call category (e.g. `report/prompt`,
  `after/rerank`, `meta/compare`); 🐞 scopes Traces to that category.
- **Reports** — one row per report; tap opens that report's Costs
  section, 🐞 scopes Traces to the report.
- **Models** — usage rolled up per model across providers.

`computeUsageGroups` also carries `getPricingStats` (which tiers are
loaded, with entry counts) on its result for the dashboard hub.

The 🧮 **Costs tiers** dashboard card opens `AiCostsTierScreen`
(`AiDashboardScreen.kt:1987`, help `ai_costs_tier`) — which pricing
tier `getPricing` would pick for every configured model, counted per
`source` via `computeTierCounts` (`data/DashboardStats.kt:375`), plus
catalog freshness.

CSV: the layered-cost CSV export/import lives on the **Costs
maintenance** screen below, not here.

## Per-report Costs breakdown

`ui/report/view/Costs.kt` (`CostsViewScreen`, help `costs_view`)
renders a report's spend. The data comes from
`rememberReportCostData(report)`
(`ui/report/manage/view/ContentDisplay.kt:598`), which has two paths:

- **Ledger fast path** — when `isApiCallCostLedgerCurrent(report)` and
  `report.apiCallCosts` is non-empty, every `CostRow` is built
  straight from the `ReportApiCallCost` ledger. No re-pricing, no
  alt-subtraction bookkeeping — the ledger already holds the final
  per-call split.
- **Reconstruction path** (legacy / incomplete ledger) — gathers and
  re-prices every call recorded against the report:
  - **Agent rows** (`type = "report/prompt"`) — use each agent's
    pinned `inputCost` / `outputCost` (frozen at the prices in effect
    when the report ran); rows with no pinned split fall back to a live
    `computeInOutCost`.
  - **Secondary results** — meta / fan-in / translate / moderation /
    rerank / tournament / judges / compare, each typed by its
    `<category>/<prompt>` (e.g. `after/rerank`, `meta/<name>`,
    `meta/compare`, the `translate/...` family) and carrying its own
    cost.
  - **Icon-gen**, **language** detect + icon, **model titles**, and
    the alternative-title / alternative-icon fan-outs. Per-call `_alt`
    rows live in `report.iconCalls`; their cost (and their tokens) is
    subtracted from the owning aggregate row so totals don't
    double-count (`ContentDisplay.kt:661-704`), clamped at 0 to absorb
    a write-ordering skew.

`CostRow` (`ui/report/manage/view/ContentDisplay.kt:1195`) carries
`type, providerDisplay, model, tier, durationMs, inputTokens,
outputTokens, inputCents, outputCents, traceFile`. Row-level cost is
stored in **cents** (Double).

The View screen rolls rows up by `bucketFor(type)` — which is now an
identity function, so the bucket keys are the raw `<category>/<prompt>`
type strings themselves (`report/prompt`, `after/rerank`,
`meta/compare`, `model/icons`, `fan/meta`, the `translate/...`
family, …). A Total card ("Current items total", in cents) sits above
a horizontal-bar list (bar length = share of total); sub-0.0001 ¢
buckets are dropped. A **Buckets ⇄ Models** toggle re-rolls the same
data by `shortModelName`. Tapping a bar drills L2 (cross-dimension) →
L3 (individual calls, paged); the 🐞 on a call opens its exact trace
via the row's `traceFile` (falling back to a newest-wins scan for
rows with none). Find-alternative-title cost is detailed in
[regenerate.md](regenerate.md).

## Manual price overrides

`ModelPricing` overrides are stored **outside** Settings — in
`PricingCache` under the `manual_pricing` map in `pricing_cache.xml`
(`KEY_MANUAL_PRICING`, `data/PricingCache.kt:47`), keyed
`"provider:model"`, **not** a `filesDir` blob. Write via
`setManualPricing` (`data/PricingCache.kt:179`), read via
`getManualPricing` / `getAllManualPricing`, drop via
`removeManualPricing`.

UI lives in two places:

- **AI Setup → Costs** — per-row CRUD
  (`ui/cruds/costsmanualoverride/`, help `crud_cost_overrides`).
  List → View → Edit / Copy / Add. Copy carries the prices and lets
  the user repoint at another model.
- **Add/Edit Override form** (`AddManualOverrideScreen`,
  `ui/admin/StatisticsScreen.kt:184`, help `cost_override`) —
  provider + model picker, input/output `$/1M tokens` fields. Prices
  are divided by 1 000 000 on save. Shows the *Current:* layered
  price (`getPricingWithoutOverride`) for reference, and supports
  duplicate-mode (👯) to clone an existing override onto a new pair.
  Also reachable pre-filled from **Model Info**
  (`ManualCostOverrideEntryScreen`, `ui/admin/StatisticsScreen.kt:143`).

Because the store isn't reactive, the CRUD bumps a refresh tick to
re-read after each write. Overrides round-trip through the backup zip
(the `pricing_cache` prefs file is in `PREFS_TO_BACKUP`).

## Costs maintenance screen

`ui/admin/CostsMaintenanceScreen.kt` (Housekeeping → **Costs**, help
`cost_config`) — the two occasional bulk operations:

- **Cleanup** — `cleanupRedundantManualOverrides`
  (`data/PricingCache.kt:204`) drops every override that is dormant or
  redundant: covered by any catalog tier (LiteLLM / models.dev /
  Helicone / llm-prices / AA / OpenRouter), equal to the built-in
  `DEFAULT`, or equal to what `getPricingWithoutOverride` would return
  anyway. Reports the count removed.
- **Layered costs CSV** — `getTierBreakdown`
  (`data/PricingCache.kt:697`) emits one row per active
  `(provider, model)` with every tier's `$/M` price (litellm,
  models.dev, helicone, llm-prices, AA, override, openrouter,
  default — computed independently). *Export all* / *Export filtered*
  (filtered drops rows already covered by a catalog tier). Fill the
  two leading `new_input_per_million` / `new_output_per_million`
  columns and re-import — only rows with values are written as
  overrides via `setManualPricing`.

`getTierBreakdown` also backs the per-model layered-cost view and the
🐞 pricing trace; `pricesConflict` (`data/PricingCache.kt:718`) flags
when ≥2 catalog tiers disagree by >1 % (override + default excluded),
and `catalogStats` lists the six bulk tiers in lookup order with entry
counts + timestamps for the Monitor hub. `clearInfoProviderTiers`
wipes the six catalog tiers but preserves manual + Together-native
pricing; `clearAll` wipes everything.

## Related docs

- [repositories.md](repositories.md) — the seven external pricing
  sources and their caches.
- [regenerate.md](regenerate.md) — Find-alternative-title call and
  how its cost is recorded.
- [tournament-judges-compare.md](tournament-judges-compare.md) —
  the three worker-judged secondary flows and their usage kinds
  (`tournament` / `judges` / `compare`).
- [persistent.md](persistent.md) — `usage-stats.json`,
  `usage-category-stats.json`, `usage-report-stats.json`,
  `pricing_cache.xml`, and the `<filesDir>/pricing/` tier blobs.
- [providers.md](providers.md) — providers, `crossProviderModelList`
  / `pricingFromModelList` self-report flags.
- [throttle.md](throttle.md) — the per-host rate/concurrency gate and
  the `ApiCallCaps` flow-level pools that bound concurrent costed
  calls.
