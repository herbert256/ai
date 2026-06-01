# UI Customization

The app has two user-editable visual systems:

- **UI Colors** — color roles backed by `AppColors`.
- **Default icons** — fallback and action glyphs backed by
  `MetadataIcons`.

Both live under **Settings**. They are app-wide preferences, are saved in
`eval_prefs`, and are included in backups/export bundles.

## UI Colors

`AppColors` is the single source for simple shared colors. The Settings →
**UI Colors** screen exposes each configurable role as a collapsed card with a
hex input, RGB sliders, a swatch, and a default reset button. Edits apply live
through `AppColors.applyUiColors`.

The stored format is a JSON map of Android ARGB ints:

```
ui_color_overrides = { "CardBackgroundAlt": -14009526, ... }
```

The legacy `ui_card_background_argb` and `ui_button_background_argb` keys still
exist for compatibility and are mirrored from the current map.

Configurable roles:

| UI label | `AppColors` key | Used for |
|---|---|---|
| App Background | `AppBackground` | Material background/surface and Android system bars |
| Main Title | `MainTitle` | Main text in the shared title bar |
| Sub Title | `SubTitle` | Subtitle text below the main title |
| Primary Accent | `PrimaryAccent` | Primary action/user-side accent |
| Secondary / Info Accent | `InfoAccent` | Secondary accents, headings, links, selected states, totals, focused fields |
| Success / Success Count Accent | `SuccessAccent` | Success states and success-count highlights |
| Danger / Error / Destructive Action Background | `DangerAccent` | Error, danger, delete, and destructive-action colors |
| Warning / Caution Accent | `WarningAccent` | Warning, caution, running, reload, pinned, throttled, and in-progress highlights |
| Queue Accent | `QueueAccent` | Queued and alternate worker/category highlights |
| Surface Dark | `SurfaceDark` | Dense dark surfaces |
| Card Background | `CardBackground` | Darker card panels, including pricing badge background |
| Card Background Alt | `CardBackgroundAlt` | Monitor/Housekeeping gray-blue card surface |
| Button Background | `ButtonBackground` | Neutral outlined button fill |
| Disabled Background | `DisabledBackground` | Disabled/unavailable surface fill |
| Selection Highlight | `SelectionHighlight` | Muted selected-state backgrounds |
| Text Primary | `TextPrimary` | Primary text and pricing badge text |
| Text Secondary / Tertiary | `TextSecondary` | Secondary and helper text |
| Text Dim / Disabled / Very Dim / Darkest | `TextDim` | Low-emphasis, disabled, and very dim text |
| Divider Dark / Border Unfocused | `BorderUnfocused` | Subtle dividers and unfocused field/swatch borders |

Several Kotlin properties remain as read-only aliases so older call sites keep
their semantic names while the user sees one setting:

| Alias property | Reads from |
|---|---|
| `SecondaryAccent` | `InfoAccent` |
| `SuccessCountAccent` | `SuccessAccent` |
| `ErrorAccent`, `DestructiveActionBackground` | `DangerAccent` |
| `CautionAccent` | `WarningAccent` |
| `TextTertiary` | `TextSecondary` |
| `TextDisabled`, `TextVeryDim`, `TextDarkest` | `TextDim` |
| `DividerDark` | `BorderUnfocused` |

The old hue names (`Purple`, `Indigo`, `Blue`, `Green`, `Red`, `Orange`, …)
are import/load fallbacks only. New saved settings use the functional names.

## Default icons

The app does not hard-code user-visible action/fallback icons in UI call sites.
Factory defaults live in `MetadataDefaults`; the editable live set is
`MetadataIcons`, persisted as one `metadata_icons` JSON blob on
`GeneralSettings`.

Composable code should read icons from `LocalMetadataIcons.current`.
Non-composable helpers use `MetadataIconsHolder.current`, which `AppNavHost`
keeps in sync with the live settings through a `SideEffect`.

Default-icons groups include:

- report/model fallback icons
- secondary result kinds (`rerank`, `meta`, `fanOutRow`, `fanInRow`,
  `tournament`, `judges`, `compare`, translation rows)
- Monitor jump icons
- create/chat/per-response actions
- navigation jumps
- configuration actions
- report-level and per-item actions
- help/view toggles
- status/progress glyphs
- marks, ranks, arrows, search/file/content/media/cost/workers/device icons

The Settings → **Default icons** screen groups those fields into collapsible
cards. Each row has:

- a label
- the current glyph
- a text field for manual entry
- an AI-icon-finder action for icon suggestions
- reset-to-default behavior

Generated metadata icons on reports and secondary rows still win over default
fallbacks. Default icons are used when a row has no generated icon and for the
app's action bars/navigation cards.

## Related files

- `ui/shared/AppColors.kt`
- `data/MetadataDefaults.kt`
- `ui/settings/SettingsScreen.kt`
- `ui/navigation/AppNavHost.kt`
- `ui/theme/Theme.kt`
- `MainActivity.kt`
