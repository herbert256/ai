# UI Customization

The app exposes two user-editable visual systems:

- **UI Colors** — the app-wide palette, backed by `AppColors`
  (`ui/shared/AppColors.kt`). Two independent colour sets (Day and
  Night) plus a mode that picks which one is painted.
- **Default icons** — the fallback/action emoji the app draws, backed by
  `MetadataIcons` (`data/MetadataDefaults.kt`).

Both are app-wide preferences. Their state is persisted in the main
`eval_prefs` SharedPreferences file (see `doc/persistent.md`) and rides
through the full backup zip and the settings import/export bundle.

They surface across **three** screens under **Settings**:

| Settings nav card | Sub-screen | Help topic | What it edits |
|---|---|---|---|
| **UI tweaks** | `SETTINGS_UI` | `settings_ui` | App home, Model-name layout, Experimental features (+ Show Knowledge card), Full-screen (default on), Show Ladybug icons |
| **UI Colors** | `SETTINGS_UI_COLORS` | `settings_ui_colors` | **Colors mode** (Day / Night / Auto) at the top, then the Day and Night colour sets (`AppColors` role overrides) |
| **Default icons** | `SETTINGS_DEFAULT_ICONS` | `settings_default_icons` | Every fallback / bottom-bar emoji (`MetadataIcons`) |

(`SettingsSubScreen` in `ui/settings/SettingsScreen.kt` drives the
two-tier in-Settings navigation; the matching `when` block routes each
value to its composable.)

### App home (Home-bar mode)

The first card on **UI tweaks** is **App home**
(`GeneralSettings.appHomeMode`, enum `AppHomeMode` in
`viewmodel/AppViewModelTypes.kt`, default `HOME_BAR`). It is a
navigation/layout choice rather than a colour/glyph customization, but it
shares the screen and the same editable glyph set:

- `HOME_BAR` (default) — a persistent top icon strip (`HomeIconBar`,
  `ui/shared/SharedComponents.kt`) on app screens; Home opens the latest
  report (or the First-launch screen).
- `HOME_SCREEN` — the classic large-card Hub.

That strip and the per-screen `BottomIconBar` / `ViewBottomBar` draw their
glyphs from the same `MetadataIcons` set documented below, so editing a
Default icon repaints them too. Persisted under the `app_home` pref key.
See `doc/manual.md` for the full home walkthrough.

A fourth screen, **Metadata & icons** (`SETTINGS_METADATA`, help topic
`settings_metadata`), is *generation* configuration — the master switch
and per-item toggles for whether the app generates report / model icons,
titles, and languages at all. It is documented in `doc/report-icons.md`
and is not part of the palette/default-icon customization covered here.

---

## UI Colors

`AppColors` is the single source for the shared colours used by cards,
buttons, text, borders, badges and status accents. It holds each role as
a `mutableStateOf` Compose state with a private setter; the rest of the
app reads `AppColors.<Role>` and recomposes when the theme is repainted.

### Two colour sets + a mode

There are two complete factory palettes, both keyed by the same role
names:

- `AppColors.DefaultUiColorArgb` — the **Night** (dark) base. Black
  background, light text. This is the original palette.
- `AppColors.DefaultUiColorArgbDay` — the **Day** (light) counterpart.
  Off-white background, dark text, accents re-tuned for a light surface.

Each set has its own per-key override map persisted separately:

| Pref key | Holds |
|---|---|
| `ui_color_overrides` | the **Night** set's per-role overrides |
| `ui_color_overrides_day` | the **Day** set's per-role overrides |
| `ui_color_mode` | a `UiColorMode` name — which set the app paints |
| `ui_card_background_argb` | legacy Int mirror of the Night `CardBackgroundAlt` override |
| `ui_button_background_argb` | legacy Int mirror of the Night `ButtonBackground` override |

`UiColorMode` (`viewmodel/AppViewModelTypes.kt`) has three values:

- `DAY` — always paint the Day set.
- `NIGHT` — always paint the Night set (default).
- `AUTO` — follow the Android system day/night setting
  (`AppColors.isDayActive(mode, systemDark)`).

The user picks the mode at the **top of the UI Colors** screen ("Colors mode" card).
The two override sets are edited on the **UI Colors** screen.

Both override maps store missing keys implicitly: a role absent from the
map falls back to that set's factory default, so the persisted JSON only
ever carries the keys the user actually changed. Stored values are
Android ARGB ints:

```jsonc
// ui_color_overrides (Night)
{ "CardBackgroundAlt": -14009526, "InfoAccent": -9651457, ... }
```

### How the live theme is painted

`AppNavHost` runs a `SideEffect` that repaints `AppColors` from the live
settings on every relevant change:

```kotlin
AppColors.applyTheme(
    dayOverrides   = generalSettings.uiColorOverridesDay,
    nightOverrides = night,   // uiColorOverrides + legacy card/button mirrors
    mode           = generalSettings.uiColorMode,
    systemDark     = isSystemInDarkTheme(),
)
```

`applyTheme` resolves Day-vs-Night via `isDayActive`, then calls
`applyResolved(day, overrides)`, which layers the chosen override map
over that set's factory base and writes each role's `mutableStateOf`.
`normalizeUiColorOverrides` is applied to the Night map first so legacy
hue aliases and the `ui_card_background_argb` / `ui_button_background_argb`
mirrors are folded in.

The **UI Colors** screen nests its own `SideEffect` below the
`AppNavHost` one, so while that screen is open it previews the set
currently being edited (`AppColors.applyResolved(editingDay, editing)`);
leaving the screen lets the `AppNavHost` effect restore the active
Colors-mode theme on the next recomposition.

### The UI Colors editor

The screen header has a **Day ☀️ / Night 🌙** segmented switch that
chooses *which set the cards edit and preview* — independent of the
Colors mode that governs what the app actually paints. The 🧽 title-bar
action clears the currently-edited set back to its factory default
(`setEditing(emptyMap())`).

Each role is a collapsible card. Collapsed, it shows the role name, its
current hex, and a swatch. Expanded, it offers:

- a 48 dp swatch,
- a **Hex** text field (`#RRGGBB`, validated; invalid input flags the
  field but doesn't write),
- a **Default** button that resets that role to the factory value *for
  the set being edited*,
- **Red / Green / Blue** 0–255 sliders,
- a **Usage** button that opens a source-derived "where is this colour
  used" screen.

Edits autosave (a 250 ms debounce plus an `onDispose` flush).

#### Combined rows

The editor does **not** present one card per `AppColors` key. A few keys
are folded so the user sees fewer, more meaningful controls — one card
writes the same value to every key it represents (`UiColorPickerSpec.key`
+ `alsoSet`):

| Card title | Writes keys |
|---|---|
| **Accent** | `PrimaryAccent` + `InfoAccent` |
| **Card Background** | `CardBackgroundAlt` + `CardBackground` |

Every other role is a standalone card. The card order is an explicit
importance order (backgrounds & titles → text → card → button → accents
→ minor surfaces → border), with any newly-added `AppColors` key
auto-appended so a future role can't silently lose its editor.

#### The "Usage" screen

Each colour card's **Usage** button opens `ColorUsageScreen`, which lists
every `AppColors.<role>` reference in the source — screen, code location,
and the coloured element/role. The list comes from `ColorUsageData`
(`data/ColorUsageData.kt`), a **build-time static scan** of the source
(`GENERATED_AT` timestamp shown at the top), not a runtime computation,
so it can drift as the code changes. Alias accessors are folded into
their canonical key, and a combined card's Usage screen unions the keys
it represents.

### Configurable roles

The factory keys (identical for both sets) and what each drives:

| `AppColors` key | Editor card | Used for |
|---|---|---|
| `AppBackground` | App Background | Full-screen background behind every screen and the Android system bars |
| `MainTitle` | Main Title | Main text in the shared title bar |
| `SubTitle` | Sub Title | Subtitle text below the main title |
| `PrimaryAccent` | Accent (combined) | Primary action / user-side accent; focused field border |
| `InfoAccent` | Accent (combined) | Secondary/detail accents, headings, links, selected states, totals, focused fields |
| `SuccessAccent` | Success | Success states and success-count highlights |
| `DangerAccent` | Error | Error, danger, delete, destructive-action colours; "real" pricing |
| `WarningAccent` | Warning | Warning, caution, running, reload, throttled, in-progress highlights |
| `QueueAccent` | Queue Accent | Queued and alternate worker/category highlights |
| `SurfaceDark` | Surface Dark | Primary dark app surface / dense surfaces |
| `CardBackground` | Card Background (combined) | Darker dense card panels |
| `CardBackgroundAlt` | Card Background (combined) | Monitor / Housekeeping gray-blue card surface |
| `ButtonBackground` | Button Background | Neutral outlined-button fill |
| `DisabledBackground` | Disabled Background | Disabled / unavailable surface fill |
| `SelectionHighlight` | Selection Highlight | Muted selected-state backgrounds |
| `TextPrimary` | Text Primary | Primary text; pricing-badge text |
| `TextSecondary` | Text Secondary | Secondary and helper/tertiary text |
| `TextDim` | Text Dimmed | Low-emphasis, disabled, very-dim, darkest text |
| `BorderUnfocused` | Border | Subtle dividers, unfocused field/swatch borders |

### Read-only aliases

Many `AppColors` properties are semantic aliases that *get* their value
from a canonical role, so older call sites keep their meaningful name
while the user edits a single setting:

| Alias property | Reads from |
|---|---|
| `SecondaryAccent` | `InfoAccent` |
| `BorderInfoFocused` | `InfoAccent` |
| `SuccessCountAccent`, `StatusOk` | `SuccessAccent` |
| `ErrorAccent`, `DestructiveActionBackground`, `StatusError`, `PricingReal` | `DangerAccent` |
| `CautionAccent` | `WarningAccent` |
| `BorderFocused` | `PrimaryAccent` |
| `TextTertiary` | `TextSecondary` |
| `TextDisabled`, `TextVeryDim`, `TextDarkest`, `StatusNotUsed`, `PricingDefault` | `TextDim` |
| `DividerDark` | `BorderUnfocused` |
| `StatusInactive` | `TextTertiary` → `TextSecondary` |

### Legacy aliases on load

`normalizeUiColorOverrides` (Night-set only) also accepts older saved
keys so an upgrade keeps a user's prior colours. The old hue names
(`Purple`, `Indigo`, `Blue`, `Green`, `Red`, `Orange`, `Yellow`,
`Brown`, …) and a handful of older role names
(`SecondaryAccent`, `SuccessCountAccent`, `ErrorAccent`,
`DestructiveActionBackground`, `CautionAccent`, `TextTertiary`,
`TextDisabled`, `DividerDark`, `IndigoHighlight`) map onto the functional
keys. These are **import/load fallbacks only**; new saved settings use
the functional names, and the `ui_card_background_argb` /
`ui_button_background_argb` Int prefs are kept as mirrors of the Night
`CardBackgroundAlt` / `ButtonBackground` overrides. As a further
back-compat step, a missing `SubTitle` override is seeded from a legacy
`WarningAccent` / `Orange` value if one is present (the subtitle used to
share the warning hue).

---

## Default icons

The app does not hard-code its user-visible action/fallback emoji at the
call sites. Factory glyph constants live in the `MetadataDefaults` object;
the editable live set is the `MetadataIcons` data class
(`data/MetadataDefaults.kt`), persisted as **one `metadata_icons` JSON
blob serialised onto `GeneralSettings`** (not a separate per-field
`eval_prefs` entry).

### How call sites read the live icons

There are two read paths, kept in sync by `AppNavHost`:

- **Composable** code reads `LocalMetadataIcons.current`
  (`ui/shared/SharedComponents.kt`), provided from the live settings.
- **Non-composable** helpers and `when`-expression sites read
  `MetadataIconsHolder.current` (`data/MetadataDefaults.kt`), which an
  `AppNavHost` `SideEffect` assigns from the live settings on every
  change.

Many shared components are passed a hard-coded factory glyph (e.g.
`MetadataDefaults.PALETTE`) and remap it to the user's override through
`MetadataIcons.forFactoryGlyph(factoryGlyph)` — it looks the factory
constant up in `factoryGlyphMap()` and returns the live glyph (or the
factory glyph unchanged if untouched). `iconizedText(text)` does the same
replacement across an entire string. This is what lets a single edit on
the Default icons screen propagate everywhere, including the home strip
and bottom action bars, whose render sites (`HomeIconBar` /
`buildBottomBarIcons` / `BottomIconBar` / the `ViewBottomBar`) read the
same `MetadataIcons` set.

`MetadataIcons.sanitized()` backfills any field left null (older stored
JSON predating it) or blank with its factory default. Gson allocates the
object without calling the Kotlin constructor, so absent fields load as
null; every load site (settings load, import) runs `sanitized()` so the
bars never draw a null/blank glyph.

### The Default icons editor

The screen (`DefaultIconsSubScreen`) is **always reachable**, independent
of the Metadata master switch, because the report/result fallbacks render
on view screens regardless. It groups every editable glyph into 18
collapsible category cards (`DEFAULT_ICON_SECTIONS`):

`Report` · `Secondary results` · `Translation` · `Monitor bar` ·
`Create & chat` · `Navigation` · `Configuration` · `Report actions` ·
`Item actions` · `View bar & help` · `Status & progress` ·
`Marks & ranks` · `Arrows` · `Search & files` · `Content & media` ·
`Cost` · `Workers & tools` · `Devices & misc`.

A collapsed category previews every glyph it contains (emoji only, no
labels, wrapping). Expanded, each row offers:

- the item label,
- an editable text field (type or paste any emoji),
- **🔎** — opens the AndroidX `EmojiPickerView` in a bottom sheet; the
  pick is written into the field,
- **🤖** — the AI icon finder: asks models for a fitting emoji (editable
  prompt) and lets you pick one,
- **📍** — a per-icon **Usage** screen showing where that glyph is drawn
  in the source.

Edits autosave (debounced + `onDispose` flush). A blank field falls back
to its factory default on save (`normalized()` walks the same row table
the UI renders). The 🧽 title-bar action resets *all* icons to factory
(`MetadataIcons()`).

### Notable groups

`MetadataIcons` carries ~172 glyph fields, among them:

- **report / model fallback** icons (`reportIcon`, `reportModelIcon`),
- **secondary-result kinds** — `rerank`, `moderate`, `meta`,
  `tournament`, `judges`, `compare`, plus `translationRow` (for
  `TRANSLATE`) and `translatorRank` 🏅 (for the `TRANSRANK`
  translator-ranking kind). `MetadataIcons.forKind(kind)` maps each of
  the **eight** `SecondaryKind` values to its glyph. The fan-out /
  fan-in row variants (`fanOutRow`, `fanInRow`) are separate
  non-`SecondaryKind` glyphs,
- **translation** — `languageIcon`, `translationRow`,
  `translationCompare`,
- bottom-bar groups: Monitor jumps, create/chat/per-response actions,
  navigation jumps, configuration, report-level and per-item actions,
  view-bar + help toggles (`help` ❓, `helpLegend` ❔),
- status/progress glyphs, marks/ranks/medals, arrows/carets, and a long
  tail of search/file/content/media/cost/workers/device symbols.

A couple of carried fields are **not** surfaced as editor rows (they're
absent from `DEFAULT_ICON_SECTIONS`, so `normalized()` and the screen
never touch them): `translatorRank` 🏅 and `reportModels` ♻️. They are
still persisted, sanitized and backed up like every other field, and the
action bars draw them via `forFactoryGlyph` / `forKind` — they just can't
be re-skinned on the Default icons screen yet.

Generated metadata icons on a report or secondary row still win over
these defaults — the defaults are the fallback when a row has no
generated icon of its own, and they back the app's action bars and
navigation cards.

---

## Related files

- `ui/shared/AppColors.kt` — the `AppColors` object: two factory
  palettes, `UiColorMode` resolution, `applyTheme` / `applyResolved`,
  alias accessors, `normalizeUiColorOverrides`.
- `data/MetadataDefaults.kt` — `MetadataDefaults` factory constants, the
  `MetadataIcons` data class, `MetadataIconsHolder`, `forFactoryGlyph` /
  `iconizedText` / `sanitized`.
- `data/ColorUsageData.kt` — the build-time source scan behind the colour
  **Usage** screen.
- `ui/settings/SettingsScreen.kt` — the **UI tweaks**, **UI Colors** and
  **Default icons** sub-screens (`UiTweaksSubScreen`, `UiColorsSubScreen`,
  `DefaultIconsSubScreen`) and their `SettingsSubScreen` routing.
- `ui/navigation/AppNavHost.kt` — the `SideEffect` that paints the theme
  (`AppColors.applyTheme`) and keeps `MetadataIconsHolder.current` /
  `LocalMetadataIcons` in sync.
- `ui/shared/SharedComponents.kt` — `LocalMetadataIcons` and the
  bottom-bar render sites.
- `viewmodel/AppViewModelTypes.kt` — `UiColorMode` and the
  `GeneralSettings` colour/icon fields.

See also `doc/persistent.md` (every prefs key), `doc/report-icons.md`
(generated icons vs these fallbacks, and the Metadata master switch),
`doc/backup-restore.md` (how these settings ride in a backup), and
`doc/manual.md` (the App home / Home-bar walkthrough). The separate
"Ranking weights" settings screen (`GeneralSettings.rankingWeights`) is
not a colour/glyph customization — it lives in `doc/value-view.md` /
`doc/rank-translators.md`.
