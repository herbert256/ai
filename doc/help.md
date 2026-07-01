# In-App Help System

Every screen in the app carries a bottom **icon bar** with help
glyphs that open per-screen help. In **Home bar** mode (Settings ->
UI tweaks -> App home), the red screen-help glyph and current-screen
trace glyph move from the bottom bar into the persistent top Home
bar; the white icon-legend helper stays in the bottom bar where it
already appears. Provider cards on Model Info /
Trace detail / Costs carry ℹ buttons that deep-link to per-provider
or per-repository help pages. The Help **home** page is a curated
set of tap-through reference cards plus a substring search box; one
of those cards (*Icons*) opens the app-wide icon legend, rendered as
a 3-column table — every title-bar / action-row icon you'll see in
the app, with a one-line description.

The bottom bar can show **two** help glyphs (see *The two help
glyphs* below): a white **❔** (`HELP_LEGEND`, U+2754, renders white)
and a red **❓** (`HELP`, U+2753, renders red — Android ignores tint
on emoji, so the colour is the glyph's own). Both are defined in
`data/MetadataDefaults.kt` and surface as `mi.helpLegend` / `mi.help`.
The red ❓ always opens the screen's main help topic; the white ❔
always opens a *live icon-legend overlay* listing the screen's own
bottom-bar icons, and shows on **every** screen that renders through
the generic `BottomIconBar` and carries at least one action icon —
not just the report-Manage family. The View family (report View
screens, plus the Model Info / provider / flock / swarm / HTML-preview
view screens, all of which route through their own `ViewBottomBar`)
shows only the red ❓; the white ❔ was removed there. A *static
icon-table help page* still exists as content, but it's reached from
a topic's main help page (the "❔ Icons on this screen" cross-link),
never directly from the bottom bar.

There is also a separate, larger **documentation** system — two
bundled HTML hubs served in a WebView (`DocumentationScreen`,
covered at the end of this doc). That is distinct from the
`HelpContent` topic catalog described here.

## Code map

| Concern | Location |
|---|---|
| Topic catalog (`HELP_TOPICS`) | `ui/admin/HelpContent.kt` — sums 12 per-domain `*Help.kt` maps + the auto-built `_icons` pages |
| Per-domain topic maps | `ui/admin/{ProviderSettingsHelp,InfoProviderHelp,GlossaryHelp,ReportsHelp,SearchHelp,LocalKnowledgeHelp,SettingsAdminHelp,DeveloperHelp,ChatHelp,ModelsHelp,ProviderCatalogHelp,CrudHelp}.kt` |
| Renderer (home + per-topic) | `ui/admin/HelpScreen.kt` |
| Per-screen + generic icon legends, `_icons` page set | `ui/admin/IconHelp.kt` |
| The two-glyph bottom bar + live overlay | `ui/shared/SharedComponents.kt` (`BottomIconBar`, `IconLegendOverlay`, `LEGEND_OVERLAY_TOPICS`) |
| `helpForTopic(topicId)` route helper | `ui/navigation/NavRoutes.kt` |
| Bundled HTML docs WebView | `ui/admin/DocumentationScreen.kt` |

`HelpContent` and `HelpCard` are `internal data class`es in
`HelpContent.kt`:

```kotlin
internal data class HelpCard(val title: String, val body: String)
internal data class HelpContent(val title: String, val cards: List<HelpCard>)
```

## Topic catalog

`HELP_TOPICS` (`HelpContent.kt`) is the master map, assembled by
adding 12 per-domain maps plus one auto-built map:

| Map file | Entries | Covers |
|---|---:|---|
| `ProviderSettingsHelp.kt` | 12 | Provider setup / config cards |
| `InfoProviderHelp.kt` | 12 | One `info_provider_*` page per metadata repository |
| `GlossaryHelp.kt` | 18 | The Help-home reference topics (see below) + `about` / `dependencies` / `manual` / `technical_documentation` |
| `ReportsHelp.kt` | 115 | The whole reports flow + every report-Manage drill-in |
| `SearchHelp.kt` | 4 | Search / local semantic search |
| `LocalKnowledgeHelp.kt` | 13 | RAG knowledge bases + on-device runtime |
| `SettingsAdminHelp.kt` | 93 | Settings sub-screens + Housekeeping |
| `DeveloperHelp.kt` | 21 | Trace, logs, developer tools |
| `ChatHelp.kt` | 9 | Chat hub, session, Dual Chat |
| `ModelsHelp.kt` | 15 | Model lists, states, info |
| `ProviderCatalogHelp.kt` | 38 | `providers` + `providers_predefined` + `provider_edit` + 35 `provider_*` per-provider pages |
| `CrudHelp.kt` | 15 | `crud_generic` overview + 14 per-CRUD topics (via the shared `crud()` helper) |

That is **365 base `HelpContent` entries**. On top of those,
`ICON_HELP_TOPIC_CONTENT` auto-builds **22** empty-bodied
`<topic>_icons` pages (one per `ICON_HELP_AS_PAGE` member; the table
itself is rendered by `HelpScreen`, not stored in the `HelpContent`),
for **387 topics** total.

Topics group, roughly, into:

- **Hub** — what the home screen does, what each card means.
- **Reports flow** — New AI Report, the model-selection screen, the
  result screen, the report *second-results* hub
  (`report_second_results`), the secondary-result flows — the **8**
  `SecondaryKind`s (Rerank / Meta / Moderation / Translate /
  Tournament / Judge-the-judges / Compare / **Rank the translators**,
  the last being `TRANSRANK`, topics `translator_rank` +
  `translator_rank_workers`) plus the Fan-out / Fan-in / Fan Meta
  drill-ins — the translation drill-ins, the Value view
  (`value_view`), the regenerate-batch screen (`regenerate_batch`),
  exports.
- **Chat flow** — Chat hub, configure-on-the-fly, chat session,
  Dual Chat.
- **Settings → AI Setup** — every sub-card has a topic. Sub-hubs
  (Models, Workers, Prompt management, Default icons, UI Colors)
  have an overview topic plus per-card detail topics. Recent
  additions include `settings_ranking_weights` (the Ranking-weights
  screen) and `broken_work` / `broken_items` (the Broken-work
  housekeeping screen).
- **Housekeeping** — Backup & Restore, Export & Import, Refresh,
  Trim by age, Update from cloud, Costs, Test, Reset.
- **Trace / developer** — Trace list, Trace detail, the
  captured-call detail view, App log.
- **Help home references** — the tap-through reference cards listed
  below, plus the auto-built `<topic>_icons` icon-table pages.
- **Per-provider** — `provider_edit` plus one `provider_*` page per
  major cloud provider (35 of the registered providers have a bespoke
  page; the rest — including every user-added provider — fall through
  to the Help home page), with setup, capabilities, quirks, known
  issues. Reachable from every ℹ icon next to a provider name (the
  page id is `providerHelpTopicId(serviceId)` = `"provider_" +
  lowercased, alphanumeric-only id`).
- **Per-repository** — 12 `info_provider_*` pages, one per external
  metadata source (HuggingFace / OpenRouter / LiteLLM / models.dev /
  Helicone / llm-prices.com / Artificial Analysis / Requesty /
  llm-stats / genai-prices / TrueFoundry / CloudPrice) with endpoint,
  auth, what it provides, when fetched, where cached. Reachable from
  every Source button on Model Info and from the Trace detail ℹ (see
  *Routing*).

### Help-home reference topics

The home page's tap-through cards point at a small set of reference
topics that **all live in `GlossaryHelp.kt`** (the `glossaryHelp`
map): `help_about`, `help_getting_started`, `concepts`,
`help_glossary` (with sub-pages `help_glossary_blocks`,
`help_glossary_groupings`, `help_glossary_operations`), `help_costs`,
`help_privacy`, `help_backup`, `help_translations`, plus the three
table-style pages `help_home_icons`, `help_home_info_providers`,
`help_home_ai_providers`. Only those **three** `help_home_*` topics
exist — there is no broader `help_home_*` family. The same
`glossaryHelp` map also carries four screen/topic pages that aren't
home-page reference cards: `about` (the About screen), `dependencies`
(the third-party-licences page), and `manual` / `technical_documentation`
(the two bundled-doc WebView screens, covered at the end). That's why
the map has 18 entries, not 14.

## The two help glyphs

`BottomIconBar` (`SharedComponents.kt`) renders the screen's action
icons at a fixed scale, filling each left-aligned row with as many
icons as fit the available width and wrapping to additional rows as
needed (the last row reserves space for the right-pinned help
glyph(s), which never count toward a row's capacity — there's no
fixed per-row cap any more). The **red ❓**
(`showScreenHelp = !suppressScreenTraceAndHelp`) navigates to the
screen's main help topic; it shows in the bottom bar by default and
is relocated to the persistent top Home bar in Home-bar mode (where
the bottom bar is rendered with `suppressScreenTraceAndHelp = true`).
A second, **white ❔** appears just to its left whenever the screen
has at least one bar icon at all: `useLegend = specs.isNotEmpty()`,
`showSecondHelp = useLegend`. Unlike the red ❓, the white ❔ isn't
gated by topic id or icon count any more — every screen using the
generic `BottomIconBar` gets it once `specs` is non-empty, and tapping
it always opens the live overlay (below). There is no longer a code
path where the bottom bar links straight to a static icon-table page.

### Live icon-legend overlay (every `BottomIconBar` screen)

On **every screen that renders through the generic `BottomIconBar`**
and shows at least one bar icon, the white ❔ opens a **full-screen
live overlay** titled "`<screen title>` - icons" (`IconLegendOverlay`).
This used to be gated to an allowlist; it no longer is — see *The two
help glyphs* above.

- The overlay lists the icons **currently visible in that screen's
  bottom bar** — two columns: the big glyph, then the icon's name +
  a one-line description.
- **Tapping a row performs exactly what tapping that bar icon does**
  (the overlay closes, then re-fires the icon's own action).
- Per-icon name + description come from `SCREEN_ICON_HELP[topic]` in
  `IconHelp.kt`, with a generic fallback map `DEFAULT_BAR_ICON_HELP`
  (keyed by glyph) for any bar icon the screen's legend doesn't
  cover.
- The overlay's own single glyph is a **red ❓** that opens the
  screen's main help page (the live overlay already covers the
  icons, so it no longer points at the standalone icon-table page).

`LEGEND_OVERLAY_TOPICS` (`SharedComponents.kt`, **57 entries** — the
whole report-Manage family: `reports_hub` and `report_run` plus its
edit / create / get-info / icons / titles overlays and sub-editors;
the Meta / secondary / fan-out / **fan-meta** drill-ins (`fan_meta`,
`fan_meta_workers`); the translation drill-ins (incl.
`translation_workers`); the tournament drill-ins (incl.
`tournament_workers`); the judge-the-judges drill-ins (`judge_eval_l1`
… `judge_eval_match`); the Find-alternative and icon-lookup screens;
the per-agent result / content / cost screens; `regenerate_batch`;
`report_notes`; `report_agent_chat`; etc.) no longer decides whether
the *bottom-bar* ❔ shows — that's now unconditional. Its remaining
job is `HelpScreen`-side: for topics in this set, `HelpScreen`
deliberately **does not** render the inline icon-table, nor the "❔
Icons on this screen" cross-link, on that topic's main help page —
the live overlay already replaced that content in the user flow. Both
suppressions check `topicId !in LEGEND_OVERLAY_TOPICS` (`HelpScreen.kt`).

### Static icon-table help page (reached from the help page, not the bottom bar)

The bottom-bar ❔ no longer links to a static page anywhere — that
path was dropped when the live overlay (above) was rolled out to
every `BottomIconBar` screen. The static `<topic>_icons` help page
still exists as *content*: a flat icon table rendered by
`IconHelpTable` from `SCREEN_ICON_HELP`, reached only via the "❔
Icons on this screen" cross-link at the top of a topic's main help
page (itself opened by the red ❓). `ICON_HELP_AS_PAGE`
(`IconHelp.kt`, 22 entries — originally curated as "whose screen
shows more than 3 icons") selects which topics get a standalone
`<topic>_icons` page; `ICON_HELP_TOPIC_CONTENT` then auto-registers a
matching empty `HelpContent` so `HELP_TOPICS.containsKey("<topic>_icons")`
succeeds and the cross-link lights up. Topics with 1–3 icons that
aren't promoted embed the same table **inline** under their main help
page instead of getting a standalone page.

Both the cross-link and the inline table are suppressed for topics in
`LEGEND_OVERLAY_TOPICS` — that screen's live overlay already covers
the icons, so the static/inline table would be redundant there. That
leaves six `ICON_HELP_AS_PAGE` topics outside `LEGEND_OVERLAY_TOPICS`
(`report_new`, `agent_edit`, `flock_edit`, `swarm_edit`, `prompt_view`,
`provider_edit`) as the topics where the standalone `<topic>_icons`
page is still reachable in the current UI — alongside, not instead
of, the live overlay from their own bottom bar.

## Topic shape

A help topic on the screen is a series of `HelpCard` rows:

```kotlin
HelpCard("Overview", "What this screen does, in one paragraph.")
HelpCard("Add card", "How to use the Add button, where it lands…")
HelpCard("Tips", "Small surprises worth knowing.")
HelpCard("Pitfalls", "Common mistakes / edge cases to avoid.")
```

Each card is a (title, body) pair; the body is plain text or
markdown-flavoured. The auto-built `<topic>_icons` pages are the one
exception — they carry an **empty** `cards` list, because their table
is rendered directly by `HelpScreen` from `SCREEN_ICON_HELP`.

The old "Related" card pattern was dropped from individual topics.
Cross-linking is now done two ways: deep-linking from the rest of
the UI (so users discover related screens from where they actually
are), and the **`RELATED_HOME_HELP`** table (`HelpContent.kt`),
which renders a *"Relevant Help pages"* footer
(`RelevantHelpPagesCard`) at the bottom of a per-topic page. Topics
with no `RELATED_HOME_HELP` entry render no footer.

## Routing

- `helpForTopic(topicId)` (`NavRoutes.kt`) builds the route string
  `"help/${encode(topicId)}"`. The topicId is URL-encoded so it can
  carry colons / slashes / spaces. `NavRoutes.HELP = "help"`.
- Every screen's `TitleBar` takes a `helpTopic: String` arg and
  publishes it (alongside the screen's action callbacks and title)
  into the bottom-bar state; the single `BottomIconBar` at AppNavHost
  scope paints the strip and the help glyph(s). In Home bar mode,
  `HomeIconBar` reads the same title-bar state and paints red help
  plus trace in the persistent top strip instead. Tapping the red ❓
  navigates to `help/{topicId}`; the white ❔ opens the live
  `IconLegendOverlay` in place, on every `BottomIconBar` screen that
  has a bar icon — it no longer navigates anywhere. (The static
  `help/{topicId}_icons` page still exists and is still reachable,
  but only from the "❔ Icons on this screen" cross-link on the
  topic's own help page, not from the bottom bar.)
- The Help **home** page (`/help`, rendered by `CompactOverview`)
  shows: a Welcome card whose text interpolates
  `AppService.entries.size` for the provider count, a "Per-screen
  help" card, then **11** tap-through reference cards in a curated
  order (About → Getting started → How it works (`concepts`) →
  Concepts & glossary → Costs → Privacy → Backup → Translations →
  Icons → Info providers → AI providers), a Copyright card, and a
  case-insensitive substring **search box**.
- Help-home search (`searchHelp`) scores every topic title (weight
  3), card title (weight 2), and card body (weight 1), collapses to
  the best card per topic, sorts descending, and takes the top 12. A
  non-blank query suppresses all other home content. Per-topic pages
  have no own search — users go back to Help home to search.
- The per-topic page chrome (`HelpFooter`) renders a "More
  information" card: a Help-home row (only on per-topic pages), an
  About row, and a `GitHub: herbert256/ai` row linking to
  <https://github.com/herbert256/ai>. Copyright is GPL v2.0, Herbert
  Groot Jebbink.

### Trace → repository help

The Trace detail screen's ℹ icon resolves the trace's URL +
category to one of the 12 repository topics via the free function
**`infoProviderForTrace(url, category)`** (`HelpScreen.kt`) — *not*
a `HelpResolver` class (there is none). It calls `infoProviderForUrl`
(host match, disambiguating shared hosts like `raw.githubusercontent.com`
via `urlPathPrefix`) and then, for dual-purpose services, gates on
category so a plain chat completion doesn't hijack the ℹ. The canonical
12-entry `INFO_PROVIDERS` list and all the resolver helpers
(`infoProviderForUrl`, `infoProviderForTrace`, `infoProviderForDisplayName`)
live in `HelpScreen.kt`. Only OpenRouter sets
`requiresChatCategoryGate = true`; its gate set is
`INFO_FETCH_CATEGORIES = setOf("OpenRouter model specs")`, plus any
category that `startsWith("pricing/")`.

## Authoring guidance

- Reuse common card patterns (Overview / Add / Tips / Pitfalls) so
  users learn the structure once. Don't bring back "Related" cards
  inside a topic — wire `RELATED_HOME_HELP` instead.
- Match topic IDs to the screen's `helpTopic` and the `HELP_TOPICS`
  map; an unmatched id renders an empty page.
- Per-provider pages share an infrastructure helper so each provider
  card has a uniform layout (`providerCatalogHelp` +
  `CLOUD_PROVIDER_TAGLINES`).
- The topic catalog (~387 entries) carries code-accurate detail and
  tips — when changing a flow, the help text deserves the same edit
  so the in-app docs stay in sync. Every full-screen overlay (model
  picker, scope picker, viewer detail, agent icon detail,
  alternative-icons list, icons grid, Find-icons picker, etc.) gets
  its own dedicated topic so help is always one tap away.
- When adding a new visual setting or default icon, update both the
  Settings topic and the focused reference doc
  [ui-customization.md](ui-customization.md).
- When adding or changing a screen's bottom-bar icons, update its
  `SCREEN_ICON_HELP[topic]` rows in `IconHelp.kt` (glyph, short name,
  screen-specific description) so the live icon-legend overlay reads
  correctly — trace the actual TitleBar handler, never guess. Every
  screen with ≥1 bar icon gets the live overlay automatically now;
  once a screen's `SCREEN_ICON_HELP` legend is complete, add its
  `helpTopic` to `LEGEND_OVERLAY_TOPICS` (`SharedComponents.kt`) so
  the topic's own help page stops duplicating the same table inline
  / via cross-link (for >3 icons, also add the topic to
  `ICON_HELP_AS_PAGE` so it gets a standalone `<topic>_icons` page
  instead of an inline one).
- New screens should get a help topic. Note that the recent **Answer
  matrix** view (`ui/report/view/AnswerMatrix.kt`, reached from the
  *Matrix* tile in the report View grid) deliberately **reuses** the
  existing `view_ai_report` topic rather than adding its own — a
  conscious exception, not an omission.

## Icon legends

There are two distinct icon legends:

- **App-wide legend** — `HelpIconTable` (`HelpScreen.kt`), a
  3-column table reached from the home page's *Icons* card
  (`help_home_icons`). It lists every title-bar / bottom-bar icon
  used across the app once, with a one-line description. This is the
  canonical app-wide reference for the action strip. (It is *not*
  shown at the top of the bare `/help` index — you tap through to the
  `help_home_icons` subpage to see it.)
- **Per-screen legends** — `SCREEN_ICON_HELP` (`IconHelp.kt`),
  describing just the icons a given screen shows, with
  screen-specific wording. These drive both the live
  `IconLegendOverlay` (every `BottomIconBar` screen with a bar icon)
  and the static `<topic>_icons` icon-table pages / inline tables
  reached from a topic's own help page, via `IconHelpTable`.
  `DEFAULT_BAR_ICON_HELP` (`IconHelp.kt`) is the per-glyph fallback
  the live overlay uses for any bar icon a screen's own legend omits.

## Bundled documentation (WebView)

Separately from the `HelpContent` catalog, `DocumentationScreen`
(`ui/admin/DocumentationScreen.kt`) is a single WebView wrapper that
serves one of two bundled HTML doc hubs, chosen by a `docsSubdir`
param:

- **Manual** — `assets/docs/manual/index.html` (just `index.html` +
  `style.css`). Route `NavRoutes.DOCUMENTATION_MANUAL`, title
  "Manual", `helpTopic = "manual"`.
- **Technical** — `assets/docs/technical/index.html` (an HTML render
  of this `doc/` set: ~19 `.html` files + `style.css`). Route
  `NavRoutes.DOCUMENTATION`, title "Technical documentation",
  `helpTopic = "technical_documentation"`.

Both routes are defined in `DeveloperRoutes.kt` and reached from
`AboutScreen` (`onOpenManual` / `onOpenTechnicalDocs`). Both
`helpTopic` ids (`manual`, `technical_documentation`) also exist as
real `HelpContent` topics in `GlossaryHelp.kt`, so the WebView's own
❓ has a page to open. JavaScript is **disabled**
(`javaScriptEnabled = false`); `allowFileAccess = true`,
`allowContentAccess = false`. System back walks the WebView's own
history (`webView.canGoBack()`) before popping the screen, giving a
normal browser back experience across cross-doc links.
