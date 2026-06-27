# Cost tracking, AI Usage, manual overrides

Every billable LLM / rerank call is costed from its token usage ×
the resolved per-model price. The same machinery feeds four
surfaces: the global **Spend & usage** dashboard, the per-report
**Costs** breakdown, the **Model Info** per-model usage card, and
the per-report **API-call cost ledger** that drives all three.

## How a call is costed

`PricingCache.computeInOutCost(usage, pricing)`
(`data/PricingCache.kt:285`) turns a `TokenUsage` + a resolved
`ModelPricing` into `(inDollars, outDollars)`:

- Base: `inputTokens × promptPrice + outputTokens × completionPrice`.
- Cached input is billed at `cachedReadPrice` (falls back to
  `promptPrice`); Anthropic cache-creation tokens at
  `cachedWritePrice`.
- **Above-200k tier** — when `inputTokens + cachedInputTokens +
  cacheCreationTokens > 200 000` and `promptPriceAbove200k` is set,
  the whole call switches to the high-context rates (Gemini 2.5/3
  Pro, legacy Anthropic Sonnet 4, …). Each
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

`computeCost(usage, pricing)` (`data/PricingCache.kt:273`) is the
single-number sibling: it returns `usage.apiCost` verbatim when the
provider reported one, otherwise `inCost + outCost` from
`computeInOutCost`.

`TokenUsage` (`data/AnalysisRepository.kt:31`) carries `inputTokens`
(the **fresh / uncached** prompt count) and `outputTokens` (both
required) plus `cachedInputTokens`, `cacheCreationTokens`,
`reasoningTokens` (these three default 0) and the optional
provider-reported `apiCost: Double?`. The fresh / cached / creation
split is done at *extraction* time in each provider's `toTokenUsage`
(`data/ApiModels.kt:856`): OpenAI-compatible `prompt_tokens` is usually
a cached-inclusive total, so the extractor subtracts the cached count
to get the fresh bucket; providers that **flatten** the cached read
into a separate field (xAI's `cached_tokens`, read after
`prompt_tokens_details.cached_tokens` / `prompt_cache_hit_tokens`) set
`promptTokensIncludeCachedTokens = false` so `prompt_tokens` passes
through as fresh input rather than being double-subtracted. Anthropic's
`input_tokens` already excludes both cache buckets, and Gemini's
`cachedContentTokenCount` is a subset of `promptTokenCount`. The cost
helpers above then bill each bucket at its own rate, so a flattened
provider's cached reads still land on `cachedReadPrice`.
`ModelPricing` (`data/PricingCache.kt:120`)
carries every rate field (per-**token**, not per-million) plus the
above-200k tier, the cache rates, `perQueryPrice`, and a `source`
string naming the tier that answered.

## Resolved pricing — layered lookup

`PricingCache.getPricing(context, provider, model)`
(`data/PricingCache.kt:379`) delegates to `findPricingMatch`
(`data/PricingCache.kt:416`), which walks tiers top→bottom, first hit
wins:

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
`PricingCache.kt:17-26` now describes the full layered order correctly,
but `getPricing`'s own KDoc at `PricingCache.kt:372-378` still calls it
a stale "five-tier lookup" — trust the `findPricingMatch` body at lines
416-433, not that comment):

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

- `getPricingWithoutOverride` (`data/PricingCache.kt:399`) mirrors the
  precedence minus step 3 — it answers "what would the layered price
  be without your override?" for the override form's *Current:* line
  and for cleanup.
- `lookupPricing(provider, model)` (`data/PricingCache.kt:410`) is the
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
searchUnits)` (`ui/settings/SettingsPreferences.kt:676`). It does four
things in one pass (and no-ops entirely when the master usage-statistics
switch is off):

1. **Live dashboard** — feeds `ApiUsageRates.record` for the rolling
   5-minute spend/token rate.
2. **Cost snapshot** — `computeUsageCostSnapshot`
   (`ui/settings/SettingsPreferences.kt:646`) prices the call via
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

The **`category`** in the `UsageStats` key is derived from the `kind`
argument with a single branch (`SettingsPreferences.kt:686-691`):

```
val normalizedKind = normalizeUsageKind(kind)
val category = if (normalizedKind == "report")
                   normalizeUsageKind(ApiTracer.currentCategory ?: "report")
               else normalizedKind
```

So **only the default `report` kind consults the trace category** — a
primary report agent (kind `report`) lands under
`ApiTracer.currentCategory` (`report/prompt`), and the parameter-sweep
replays land under their normalized labels (Temperature sweep →
`model/temperature`, etc.). **Every explicit non-`report` kind wins
directly and the trace category is ignored** — so a rerank lands under
`rerank`, a meta under `meta`, a translation under `translate`, an
icon-gen call under `icon`, and so on. (The richer slash-typed labels
`after/rerank`, `meta/<promptName>`, `transrank/rank`, the
`translate/...` family, … are not what the live usage store records;
they are the *structured* ledger types rebuilt by
`reconcileApiCallCostLedger` — see the next two sections.)
`normalizeUsageKind` (`data/ApiCallKinds.kt:28`) maps the legacy sweep
labels and defaults a `null` kind to `"report"`. The kinds passed
explicitly are the eight `SecondaryKind` strings — `rerank`,
`moderation`, `meta`, `translate`, `tournament`, `judges`, `compare`,
`transrank` (mapped in `SecondaryRunManager.kt:1659`) — plus `icon`,
`title`, `language`, `language-icon`, the chat kinds (`Chat`, `Dual
chat`, `chat/rag`), `all`, and `settings/icons`.

`UsageStats` (`model/SettingsModels.kt:1061`) holds `callCount`,
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
(`SettingsPreferences.kt:496`) backfills them to `"report"` so the
non-null contract holds. Parse is per-row, so a single unresolvable
provider id (a deleted custom provider) doesn't drop the whole file;
if *every* row fails (ProviderRegistry not yet initialised) the cache
is left null so the next read retries.

`clearUsageStats` (`SettingsPreferences.kt:970`) clears all three
caches, resets the flush timestamp, and deletes the three JSON files.

## Per-report API-call cost ledger

Each costed call also appends a `ReportApiCallCost`
(`data/ReportModels.kt:161`) row to the owning report's
`apiCallCosts` list when `ApiTracer.currentReportId` is set — `id`,
`timestamp`, `type` (the live usage `category` above), `provider`,
`model`, `pricingTier`, in/out tokens, in/out cost, `searchUnits`,
`durationMs`, `traceFile`. `API_CALL_COST_LEDGER_VERSION = 3`
(`data/ReportStorage.kt:40`); a report whose `apiCallCostsComplete`
flag is set and whose `apiCallCostsVersion >= 3` is treated as a
complete ledger (`isApiCallCostLedgerCurrent`,
`data/ReportStorage.kt:1499`).

The **live** append uses the bare usage `category` as the row `type`,
and never flips `apiCallCostsComplete`. The canonical
`<category>/<prompt>` types (`report/prompt`, `after/rerank`,
`after/moderation`, `after/tournament`, `after/judges`,
`meta/<prompt>`, `meta/compare`, `fan_out/...` / `fan_in/...`,
`transrank/rank`, the `translate/...` family) are produced by
`reconcileApiCallCostLedger` (`data/ReportStorage.kt:1502`), which
**rebuilds** the whole ledger from the structured agent / secondary /
icon rows via `buildStructuredApiCallCostRows`
(`data/ReportStorage.kt:1592`), marks it complete + version 3, and
recomputes `totalCost`. That reconcile runs lazily — from the Spend &
usage screen (`reconcileReportCostLedgers`,
`SettingsPreferences.kt:591`) and from the per-report cost views — so a
report's displayed types are always the structured ones once it has
been reconciled.

Two correctness guards live on the live-append path:

- `appendApiCallCost` (`data/ReportStorage.kt:1468`) returns **null on
  a dedup hit** (`record.id` already present), otherwise a
  `ReportApiCallAppendResult`. The caller
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
(`data/ReportStorage.kt:128`) has two paths:

- **Ledger total** — when `isApiCallCostLedgerCurrent(report)`,
  `ledgerTotalCost` (`data/ReportStorage.kt:1589`) just sums every
  `ReportApiCallCost` row's `inputCost + outputCost`. This is the
  source of truth for reconciled reports, so a new call category can
  never be silently omitted from a hard-coded allow-list.
- **Legacy total** — `legacyReportTotalCost`
  (`data/ReportStorage.kt:131`) sums agent primary cost + per-agent
  icon + per-agent model-title + report-level
  icon/title/titleLong/language/languageIcon costs + the Find-alt
  title fan-out `iconCalls` of `TITLE_ALT_TYPES =
  {"alt/report_title","alt/report_title_long","alt/model_title"}`
  (`data/ReportStorage.kt:47`) whose `attributedToSecondaryId == null`
  + the user-note AI-title `iconCalls` of type `"note/title"` — the two
  alt categories with no structured cost home.

`costsFromDeletedItems` is tracked separately and intentionally
**excluded** from this number — the Manage bottom bar and HTML export
add it back in, the View Costs screen does not (it shows "Current items
total"). When a report's own models were the fan-out answerers, the
**Value view** folds each model's fan-out response spend into its
plotted per-model cost (skipped when two success agents share a
provider/model key) — that cost mechanic is detailed in
[value-view.md](value-view.md).

## Spend & usage dashboard

Reached from the **Statistics** screen (`AiStatisticsScreen`,
`ui/admin/AiDashboardScreen.kt`, help `ai_statistics`) as the 💰
**Spend & usage** link-card.
The screen is `AiSpendUsageScreen`
(`ui/admin/AiDashboardScreen.kt:1177`, help `ai_spend_usage`). On open
it does a one-time OpenRouter pricing refresh (when stale) then
computes its breakdown via `computeUsageGroups`
(`data/DashboardStats.kt:156`) off the main thread — heavy because it
calls `getPricing` per model, and because it first runs
`reconcileReportCostLedgers` to bring every report's ledger current.
Rerank rows fold `searchUnits × perQueryPrice` into their cost.

A **Total card** (calls / tokens / cost, green) sits above a
four-tab body (`SpendUsageMode`: **Providers / Models / Types /
Reports**):

- **Providers** — one row per provider (Provider / Calls / Tokens /
  Cost / 🐞), sortable by any column header (default cost,
  descending). The 🐞 opens API Traces scoped to that provider and
  shows only when that provider has a captured trace. Tapping a row
  opens **`AiSpendUsageProviderScreen`**
  (`AiDashboardScreen.kt:1664`, help `ai_usage_provider`) — the
  per-provider breakdown grouped by call kind, by pricing source
  (OVERRIDE / OPENROUTER / LITELLM / …), and by model; each model row
  links to that model's **Model Info** page.
- **Models** — usage rolled up per model across providers.
- **Types** — one row per live usage `category` from
  `UsageCategoryStats` (e.g. `report/prompt` for primary agents, and
  the bare-kind buckets `rerank` / `meta` / `transrank` / `icon` / … for
  everything else — *not* the slash-typed ledger types); 🐞 scopes
  Traces to that category.
- **Reports** — one row per report; tap opens that report's Costs
  section, 🐞 scopes Traces to the report.

`computeUsageGroups` also carries `getPricingStats` (which tiers are
loaded, with entry counts) on its result for the dashboard hub.

The 🧮 **Costs tiers** dashboard card opens `AiCostsTierScreen`
(`AiDashboardScreen.kt:1990`, help `ai_costs_tier`) — which pricing
tier `getPricing` would pick, counted per `source`, in two side-by-side
columns: **Config** (every configured model, via `computeTierCounts`,
`data/DashboardStats.kt:374`) and **Runtime** (only the models actually
called, read from the API traces via `computeTierCountsRuntime`,
`data/DashboardStats.kt:397`), plus catalog freshness from
`catalogStats`.

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
  - **Secondary results** — all eight `SecondaryKind`s (meta /
    fan-out / fan-in / rerank / moderation / translate / tournament /
    judges / compare / transrank), each typed by its
    `<category>/<prompt>` (`after/rerank`, `after/moderation`,
    `after/tournament`, `after/judges`, `meta/<name>`, `meta/compare`,
    `transrank/rank`, the `translate/...` family — the mapping at
    `ContentDisplay.kt:937`) and carrying its own cost.
  - **Icon-gen**, **language** detect + icon, **model titles**, and
    the alternative-title / alternative-icon fan-outs. Per-call `_alt`
    rows live in `report.iconCalls`; their cost (and their tokens) is
    subtracted from the owning aggregate row so totals don't
    double-count (`ContentDisplay.kt:661-704`), clamped at 0 to absorb
    a write-ordering skew.

`CostRow` (`ui/report/manage/view/ContentDisplay.kt:1197`) carries
`type, providerDisplay, model, tier, durationMs, inputTokens,
outputTokens, inputCents, outputCents, traceFile`. Row-level cost is
stored in **cents** (Double).

The View screen (`ui/report/view/Costs.kt`) rolls rows up by
`bucketFor(type)` (`Costs.kt:383`) — which is now an identity function,
so the bucket keys are the raw `<category>/<prompt>` type strings
themselves (`report/prompt`, `after/rerank`, `meta/compare`,
`transrank/rank`, `fan/meta`, the `translate/...` family, …). A Total
card ("Current items total", in cents) sits above
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
(`KEY_MANUAL_PRICING`, `data/PricingCache.kt:52`), keyed
`"provider:model"`, **not** a `filesDir` blob. Write via
`setManualPricing` (`data/PricingCache.kt:186`), read via
`getManualPricing` / `getAllManualPricing`, drop via
`removeManualPricing`.

UI lives in two places:

- **AI Setup → Costs** — per-row CRUD
  (`ui/cruds/costsmanualoverride/`, help `crud_cost_overrides`).
  List → View → Edit / Copy / Add. Copy carries the prices and lets
  the user repoint at another model.
- **Add/Edit Override form** (`AddManualOverrideScreen`,
  `ui/admin/StatisticsScreen.kt:186`, help `cost_override`) —
  provider + model picker, input/output `$/1M tokens` fields. Prices
  are divided by 1 000 000 on save. Shows the *Current:* layered
  price (`getPricingWithoutOverride`) for reference, and supports
  duplicate-mode (👯) to clone an existing override onto a new pair.
  Also reachable pre-filled from **Model Info**
  (`ManualCostOverrideEntryScreen`, `ui/admin/StatisticsScreen.kt:145`).

Because the store isn't reactive, the CRUD bumps a refresh tick to
re-read after each write. Overrides round-trip through the backup zip
(the `pricing_cache` prefs file is in `PREFS_TO_BACKUP`).

## Costs maintenance screen

`ui/admin/CostsMaintenanceScreen.kt` (Housekeeping → **Costs**, help
`cost_config`) — the two occasional bulk operations:

- **Cleanup** — `cleanupRedundantManualOverrides`
  (`data/PricingCache.kt:211`) drops every override that is dormant or
  redundant: covered by any catalog tier (LiteLLM / models.dev /
  Helicone / llm-prices / AA / OpenRouter), equal to the built-in
  `DEFAULT`, or equal to what `getPricingWithoutOverride` would return
  anyway. Reports the count removed.
- **Layered costs CSV** — `buildLayeredCsv` emits one row per active
  `(provider, model)` (via `getTierBreakdown`,
  `data/PricingCache.kt:698`) with every tier's `$/M` price (litellm,
  models.dev, helicone, llm-prices, AA, override, openrouter,
  default — computed independently; the `together` field of
  `TierBreakdown` is not exported). *Export all* / *Export filtered*
  (filtered drops rows already covered by a catalog tier). Fill the
  two leading `new_input_per_million` / `new_output_per_million`
  columns and re-import — only rows with values are written as
  overrides via `setManualPricing` (a single-column edit keeps the
  other side at its current lookup value).

`getTierBreakdown` also backs the per-model layered-cost view and the
🐞 pricing trace; `pricesConflict` (`data/PricingCache.kt:719`) flags
when ≥2 catalog tiers disagree by >1 % (override + default excluded),
and `catalogStats` (`data/PricingCache.kt:670`) lists the six bulk
tiers in lookup order with entry counts + timestamps for the Monitor
hub. `clearInfoProviderTiers` wipes the six catalog tiers but preserves
manual + Together-native pricing; `deleteTier` drops one named tier;
`clearAll` wipes everything.

## Related docs

- [repositories.md](repositories.md) — the seven external pricing
  sources and their caches.
- [regenerate.md](regenerate.md) — Find-alternative-title call and
  how its cost is recorded.
- [tournament-judges-compare.md](tournament-judges-compare.md) —
  the three worker-judged secondary flows and their usage kinds
  (`tournament` / `judges` / `compare`).
- [value-view.md](value-view.md) — the cost × quality (Pareto) view and
  its fan-out response-cost fold-in (the cost mechanic referenced under
  *Per-report total*); also the home of the `transrank` ("Rank the
  translators") flow whose ledger type is `transrank/rank`.
- [persistent.md](persistent.md) — `usage-stats.json`,
  `usage-category-stats.json`, `usage-report-stats.json`,
  `pricing_cache.xml`, and the `<filesDir>/pricing/` tier blobs.
- [providers.md](providers.md) — providers, `crossProviderModelList`
  / `pricingFromModelList` self-report flags.
- [throttle.md](throttle.md) — the per-host rate/concurrency gate and
  the `ApiCallCaps` flow-level pools that bound concurrent costed
  calls.
