# In-App Help System

Every screen in the app carries a bottom **icon bar** with help
glyphs that open per-screen help. Provider cards on Model Info /
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
either opens a *live icon-legend overlay* (on report-Manage screens)
or links to a *static icon-table help page* (everywhere else).

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
| `InfoProviderHelp.kt` | 7 | One `info_provider_*` page per metadata repository |
| `GlossaryHelp.kt` | 17 | The Help-home reference topics (see below) + `manual` / `technical_documentation` |
| `ReportsHelp.kt` | 103 | The whole reports flow + every report-Manage drill-in |
| `SearchHelp.kt` | 4 | Search / local semantic search |
| `LocalKnowledgeHelp.kt` | 13 | RAG knowledge bases + on-device runtime |
| `SettingsAdminHelp.kt` | 79 | Settings sub-screens + Housekeeping |
| `DeveloperHelp.kt` | 21 | Trace, logs, developer tools |
| `ChatHelp.kt` | 9 | Chat hub, session, Dual Chat |
| `ModelsHelp.kt` | 14 | Model lists, states, info |
| `ProviderCatalogHelp.kt` | 44 | `provider_edit` + 43 `provider_*` per-provider pages |
| `CrudHelp.kt` | 1 | Shared CRUD overview |

That is **325 base `HelpContent` entries**. On top of those,
`ICON_HELP_TOPIC_CONTENT` auto-builds **22** empty-bodied
`<topic>_icons` pages (one per `ICON_HELP_AS_PAGE` member; the table
itself is rendered by `HelpScreen`, not stored in the `HelpContent`),
for **~347 topics** total.

Topics group, roughly, into:

- **Hub** — what the home screen does, what each card means.
- **Reports flow** — New AI Report, the model-selection screen, the
  result screen, the secondary-result flows (Meta / Fan-out /
  Fan-in / Rerank / Moderation / Tournament / Judge-the-judges /
  Compare), the translation drill-ins, exports.
- **Chat flow** — Chat hub, configure-on-the-fly, chat session,
  Dual Chat.
- **Settings → AI Setup** — every sub-card has a topic. Sub-hubs
  (Models, Workers, Prompt management, Default icons, UI Colors)
  have an overview topic plus per-card detail topics.
- **Housekeeping** — Backup & Restore, Export & Import, Refresh,
  Trim by age, Update from cloud, Costs, Test, Reset.
- **Trace / developer** — Trace list, Trace detail, the
  captured-call detail view, App log.
- **Help home references** — the tap-through reference cards listed
  below, plus the auto-built `<topic>_icons` icon-table pages.
- **Per-provider** — `provider_edit` plus one `provider_*` page per
  cloud provider, with setup, capabilities, quirks, known issues.
  Reachable from every ℹ icon next to a provider name (the page id is
  `providerHelpTopicId(serviceId)` = `"provider_" + lowercased,
  alphanumeric-only id`).
- **Per-repository** — seven `info_provider_*` pages, one per
  external metadata source (HuggingFace / OpenRouter / LiteLLM /
  models.dev / Helicone / llm-prices.com / Artificial Analysis) with
  endpoint, auth, what it provides, when fetched, where cached.
  Reachable from every Source button on Model Info and from the
  Trace detail ℹ (see *Routing*).

### Help-home reference topics

The home page's tap-through cards point at a small set of reference
topics that **all live in `GlossaryHelp.kt`** (the `glossaryHelp`
map): `help_about`, `help_getting_started`, `concepts`,
`help_glossary` (with sub-pages `help_glossary_blocks`,
`help_glossary_groupings`, `help_glossary_operations`), `help_costs`,
`help_privacy`, `help_backup`, `help_translations`, plus the three
table-style pages `help_home_icons`, `help_home_info_providers`,
`help_home_ai_providers`. Only those **three** `help_home_*` topics
exist — there is no broader `help_home_*` family.

## The two help glyphs

`BottomIconBar` (`SharedComponents.kt`) renders the screen's action
icons (up to 7 per row, wrapping to additional left-aligned rows)
and pins the help glyph(s) to the right of the **last** row; the
glyphs never count toward the 7-per-row cap. The **red ❓** is always
present and navigates to the screen's main help topic. A second,
**white ❔** appears just to its left in two distinct behaviours,
selected by `useLegend = (icons?.helpTopic in LEGEND_OVERLAY_TOPICS)
&& specs.isNotEmpty()`:

- `showLegendHelp = useLegend`
- `showIconPageHelp = !useLegend && iconTopic != null && specs.size > 3`
- `showSecondHelp = showLegendHelp || showIconPageHelp`

### Live icon-legend overlay (report-Manage screens)

On screens whose `helpTopic` is in `LEGEND_OVERLAY_TOPICS`
(`SharedComponents.kt`, ~50 entries — the whole report-Manage
family: `report_run` plus its edit / create / get-info / icons /
titles overlays and sub-editors; the Meta / secondary / fan-out
drill-ins; the translation, tournament, and judge-the-judges
drill-ins; the Find-alternative and icon-lookup screens; the
per-agent result / content / cost screens; `report_notes`;
`report_agent_chat`; etc.) — the white ❔ opens a **full-screen live
overlay** titled "`<screen title>` - icons" (`IconLegendOverlay`).

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
- On these screens the white ❔ shows **whenever there is ≥1 bar
  icon** (`useLegend && specs.isNotEmpty()`) — the old "min 3 icons"
  rule no longer gates it.

On these overlay screens, `HelpScreen` deliberately **does not**
render the inline icon-table, nor the "❔ Icons on this screen"
cross-link — the live overlay replaced the static `<topic>_icons`
page in the user flow. Both suppressions check
`topicId !in LEGEND_OVERLAY_TOPICS` (`HelpScreen.kt`).

### Static icon-table help page (everywhere else)

On **non-overlay screens** (Settings, CRUD, Chat, etc.) the white ❔
**navigates to the static `<topic>_icons` help page** — a flat icon
table rendered by `IconHelpTable` from `SCREEN_ICON_HELP`. It appears
only when the screen has its own `<topic>_icons` page **and** more
than 3 action icons (the "crowded bar" rule, `specs.size > 3`).
Screens with a `<topic>_icons` page also get the "❔ Icons on this
screen" cross-link at the top of their main help page. Topics that
show 1–3 icons embed the same table **inline** under their main help
page instead of getting a standalone page. `ICON_HELP_AS_PAGE`
(`IconHelp.kt`, 22 entries) selects which topics are promoted to
their own page; `ICON_HELP_TOPIC_CONTENT` then auto-registers a
matching empty `HelpContent` so `HELP_TOPICS.containsKey("<topic>_icons")`
succeeds and the ❔ glyph + cross-link light up.

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
  scope paints the strip and the help glyph(s). Tapping the red ❓
  navigates to `help/{topicId}`; the white ❔ either opens the live
  `IconLegendOverlay` (on `LEGEND_OVERLAY_TOPICS` screens) or
  navigates to `help/{topicId}_icons` (the static icon page).
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
category to one of the seven repository topics via the free function
**`infoProviderForTrace(url, category)`** (`HelpScreen.kt`) — *not*
a `HelpResolver` class (there is none). It calls `infoProviderForUrl`
(host match, disambiguating shared hosts like `raw.githubusercontent.com`
via `urlPathPrefix`) and then, for dual-purpose services, gates on
category so a plain chat completion doesn't hijack the ℹ. The canonical
seven-entry `INFO_PROVIDERS` list and all the resolver helpers
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
- The topic catalog (~347 entries) carries code-accurate detail and
  tips — when changing a flow, the help text deserves the same edit
  so the in-app docs stay in sync. Every full-screen overlay (model
  picker, scope picker, viewer detail, agent icon detail,
  alternative-icons list, icons grid, Find-icons picker, etc.) gets
  its own dedicated topic so help is always one tap away.
- When adding a new visual setting or default icon, update both the
  Settings topic and the focused reference doc
  [ui-customization.md](ui-customization.md).
- When adding or changing a report-Manage screen's bottom-bar icons,
  update its `SCREEN_ICON_HELP[topic]` rows in `IconHelp.kt` (glyph,
  short name, screen-specific description) so the live icon-legend
  overlay reads correctly — trace the actual TitleBar handler, never
  guess. Add the screen's `helpTopic` to `LEGEND_OVERLAY_TOPICS` to
  give it the live overlay; otherwise it falls back to the static
  `<topic>_icons` page path (and, for >3 icons, add the topic to
  `ICON_HELP_AS_PAGE`).
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
  screen-specific wording. These drive both the static
  `<topic>_icons` icon-table pages (non-overlay screens, via
  `IconHelpTable`) and the live `IconLegendOverlay` (report-Manage
  screens). `DEFAULT_BAR_ICON_HELP` (`IconHelp.kt`) is the per-glyph
  fallback the live overlay uses for any bar icon a screen's own
  legend omits.

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
