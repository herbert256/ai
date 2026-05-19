# In-App Help System

Every screen in the app carries a TitleBar with a ❓ icon that opens
a per-screen help topic. Provider cards on Model Info / Trace
detail / Costs carry ℹ buttons that deep-link to per-provider help
pages. The Help home page surfaces an icon legend rendered as a
3-column table — every TitleBar icon you'll see in the app, with a
one-line description.

Code lives in `ui/admin/HelpContent.kt` (the topic catalog —
~190 topics, one per full-screen overlay) and
`ui/admin/HelpScreen.kt` (renderer + `HelpResolver`). The
`helpForTopic(topicId)` route helper is in
`ui/navigation/NavRoutes.kt`.

## Topic catalog

Topics group into:

- **Hub** — what the home screen does, what each card means.
- **Reports flow** — New AI Report, the model selection screen, the
  result screen, the secondary-result flows, exports.
- **Chat flow** — Chat hub, configure-on-the-fly, chat session,
  Dual Chat.
- **Settings → AI Setup** — every sub-card has a topic. Sub-hubs
  (Models, Workers, Prompt management) have their own
  overview topic plus per-card detail topics.
- **Housekeeping** — Backup & Restore, Export & Import, Refresh,
  Trim by age, Usage statistics, Reset.
- **Trace** — Trace list, Trace detail, the captured-call detail
  view.
- **Help** — the home help page, including the icon legend.
- **Per-provider** — one page per active provider with setup,
  capabilities, quirks, and known issues. Reachable from every ℹ
  icon next to a provider name.
- **Per-repository** — one page per external metadata source
  (LiteLLM / OpenRouter / models.dev / Helicone / llm-prices /
  Artificial Analysis / HuggingFace) with endpoint, auth, what it
  provides, when fetched, where cached. Reachable from every Source
  button on Model Info.

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
- Every TitleBar takes a `helpTopic: String` arg. Tapping ❓
  navigates to `help/{topicId}`.
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
- The topic catalog (~190 entries) is rewritten end-to-end with
  code-accurate detail and tips — when changing a flow, the help
  text deserves the same edit so the in-app docs stay in sync.
  In particular, every full-screen overlay (model picker, scope
  picker, viewer detail, agent icon detail, alternative-icons
  list, icons grid, Find icons picker, etc.) gets its own
  dedicated topic so help is always one tap away.

## Icon legend

Rendered as a 3-column table on the Help home page. Every TitleBar
icon used across the app is listed once, with a one-line
description. The icon legend is the canonical reference for the
TitleBar action strip.
