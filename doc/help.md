# In-App Help System

Every screen in the app carries a bottom **icon bar** with help
glyphs that open per-screen help. Provider cards on Model Info /
Trace detail / Costs carry ℹ buttons that deep-link to per-provider
help pages. The Help home page surfaces an icon legend rendered as a
3-column table — every title-bar icon you'll see in the app, with a
one-line description.

The bottom bar can show **two** help glyphs (see *The two help
glyphs* below): a white **❔** (U+2754, renders white) and a red
**❓** (U+2753, renders red — Android ignores tint on emoji, so the
colour is the glyph's own). The red ❓ always opens the screen's
main help topic; the white ❔ either opens a *live icon-legend
overlay* (on report-Manage screens) or links to a *static
icon-table help page* (everywhere else).

Code lives in `ui/admin/HelpContent.kt` (the topic catalog —
~280 `HelpContent` topics, plus auto-built `<topic>_icons` pages),
`ui/admin/HelpScreen.kt` (the renderer), `ui/admin/IconHelp.kt`
(the per-screen + generic icon legends), and
`ui/shared/SharedComponents.kt` (the `BottomIconBar` that hosts the
two glyphs and the `IconLegendOverlay`). The `helpForTopic(topicId)`
route helper is in `ui/navigation/NavRoutes.kt`.

## Topic catalog

Topics group into:

- **Hub** — what the home screen does, what each card means.
- **Reports flow** — New AI Report, the model selection screen, the
  result screen, the secondary-result flows, exports.
- **Chat flow** — Chat hub, configure-on-the-fly, chat session,
  Dual Chat.
- **Settings → AI Setup** — every sub-card has a topic. Sub-hubs
  (Models, Workers, Prompt management, Default icons, UI Colors)
  have their own overview topic plus per-card detail topics.
- **Housekeeping** — Backup & Restore, Export & Import, Refresh,
  Trim by age, Update from cloud, Costs, Test, Reset.
- **Trace** — Trace list, Trace detail, the captured-call detail
  view.
- **Help** — the home help page, including the icon legend, plus
  the auto-built `<topic>_icons` icon-table pages.
- **Per-provider** — one page per active provider with setup,
  capabilities, quirks, and known issues. Reachable from every ℹ
  icon next to a provider name.
- **Per-repository** — one page per external metadata source
  (LiteLLM / OpenRouter / models.dev / Helicone / llm-prices /
  Artificial Analysis / HuggingFace) with endpoint, auth, what it
  provides, when fetched, where cached. Reachable from every Source
  button on Model Info.

## The two help glyphs

The bottom icon bar (`BottomIconBar` in `SharedComponents.kt`)
renders the screen's action icons on the left and pins the help
glyph(s) to the right of the last row. The **red ❓** is always
present and navigates to the screen's main help topic. A second,
**white ❔** appears to its left in two distinct behaviours:

### Live icon-legend overlay (report-Manage screens)

On the screens whose `helpTopic` is in the `LEGEND_OVERLAY_TOPICS`
set (`SharedComponents.kt`) — the whole report-Manage family:
`report_run` plus its edit / create / icons / titles overlays and
sub-editors, the Meta / secondary / fan-out drill-ins, the
translation drill-ins, the Find-alternative and icon-lookup
screens, the per-agent result / content / cost screens, etc. — the
white ❔ opens a **full-screen live overlay** titled
"`<screen title>` - icons" (`IconLegendOverlay`).

- The overlay lists the icons **currently visible in that screen's
  bottom bar** — two columns: the big glyph, then the icon's name +
  a one-line description.
- **Tapping a row performs exactly what tapping that bar icon does**
  (the overlay closes, then re-fires the icon's own action).
- Per-icon name + description come from `SCREEN_ICON_HELP[topic]` in
  `IconHelp.kt`, with a generic fallback map
  `DEFAULT_BAR_ICON_HELP` (keyed by glyph) for any bar icon the
  screen's legend doesn't cover.
- The overlay's own single icon is a **red ❓** that opens the
  screen's main help page (the live overlay already covers the
  icons, so it no longer points at the standalone icon-table page).
- On these screens the white ❔ shows **whenever there is ≥1 bar
  icon** (`useLegend && specs.isNotEmpty()`) — the old "min 3 icons"
  rule no longer gates it.

On these overlay screens, `HelpScreen` deliberately **does not**
render the inline icon-table, nor the "❔ Icons on this screen"
cross-link — the live overlay replaced the static `<topic>_icons`
page in the user flow (both suppressions check
`topicId in LEGEND_OVERLAY_TOPICS`).

### Static icon-table help page (everywhere else)

On **non-overlay screens** (Settings, CRUD, Chat, etc.) the white
❔ is unchanged: it **navigates to the static `<topic>_icons`
help page** — a flat icon table rendered by `IconHelpTable` from
`SCREEN_ICON_HELP`. There it appears only when the screen has its
own `<topic>_icons` page **and** more than 3 action icons (the
"crowded bar" rule, `specs.size > 3`). Screens with a
`<topic>_icons` page also get the "❔ Icons on this screen"
cross-link at the top of their main help page. Topics that show
1–3 icons embed the same table **inline** under their main help
page instead of getting a standalone page (`ICON_HELP_AS_PAGE`
selects which topics are promoted to their own page).

## Topic shape

A help topic on the screen is a series of `HelpCard` rows:

```kotlin
HelpCard("Overview", "What this screen does, in one paragraph.")
HelpCard("Add card", "How to use the Add button, where it lands…")
HelpCard("Tips", "Small surprises worth knowing.")
HelpCard("Pitfalls", "Common mistakes / edge cases to avoid.")
```

Each card is a (title, body) pair; the body is plain text or
markdown-flavoured. The "Related" card pattern was dropped
across the catalog — link by deep-linking from the rest of the
UI instead, so users discover related screens from where they
actually are rather than from a list inside Help.

## Routing

- `helpForTopic(topicId)` builds the route string. The topicId is
  URL-encoded so it can carry colons / slashes / spaces.
- Every screen's `TitleBar` takes a `helpTopic: String` arg and
  publishes it (alongside the screen's action callbacks and title)
  into `LocalBottomIconState`; the single `BottomIconBar` at
  AppNavHost scope paints the strip and the help glyph(s). Tapping
  the red ❓ navigates to `help/{topicId}`; the white ❔ either opens
  the live `IconLegendOverlay` (on `LEGEND_OVERLAY_TOPICS` screens)
  or navigates to `help/{topicId}_icons` (the static icon page).
- The Trace detail screen's ℹ icon resolves the trace's URL +
  category to one of the 7 repository topics via `HelpResolver` —
  the resolver is gated on a small `INFO_FETCH_CATEGORIES` set so
  unrelated traces don't get a misleading provider help.
- The home Help page (`/help`) surfaces the icon legend at the top
  and topic-group navigation below.

## Authoring guidance

- Reuse common card patterns (Overview / Add / Tips / Pitfalls)
  so users learn the structure once. Don't bring back "Related"
  cards.
- Match the topic IDs in `NavRoutes` to the help screen's switch.
- Per-provider pages share an infrastructure helper so each provider
  card has a uniform layout.
- The topic catalog (~280 entries) carries code-accurate detail
  and tips — when changing a flow, the help text deserves the same
  edit so the in-app docs stay in sync. In particular, every
  full-screen overlay (model picker, scope picker, viewer detail,
  agent icon detail, alternative-icons list, icons grid, Find
  icons picker, etc.) gets its own dedicated topic so help is
  always one tap away.
- When adding a new visual setting or default icon, update both the
  Settings topic and the focused reference doc
  [ui-customization.md](ui-customization.md).
- When adding or changing a report-Manage screen's bottom-bar
  icons, update its `SCREEN_ICON_HELP[topic]` rows in
  `IconHelp.kt` (glyph, short name, screen-specific description) so
  the live icon-legend overlay reads correctly — trace the actual
  TitleBar handler, never guess. Add the screen's `helpTopic` to
  `LEGEND_OVERLAY_TOPICS` to give it the live overlay; otherwise it
  falls back to the static `<topic>_icons` page path.

## Icon legend

Rendered as a 3-column table on the Help home page (`HelpIconTable`
in `HelpScreen.kt`). Every title-bar / bottom-bar icon used across
the app is listed once, with a one-line description. This home-page
legend is the canonical app-wide reference for the action strip.

Distinct from it are the **per-screen** legends in `IconHelp.kt`
(`SCREEN_ICON_HELP`), which describe just the icons a given screen
shows, with screen-specific wording. Those drive both the static
`<topic>_icons` icon-table pages (non-overlay screens) and the
live `IconLegendOverlay` (report-Manage screens); the generic
`DEFAULT_BAR_ICON_HELP` map is the per-glyph fallback the live
overlay uses for any bar icon a screen's own legend omits.
