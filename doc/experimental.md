# Experimental features — what the master toggle hides

The app carries a single master gate, **Experimental features**
(`GeneralSettings.experimentalFeaturesEnabled`, prefs key
`experimental_features`, **default `false`**). It is the on/off switch
for a cluster of in-progress / advanced surfaces that aren't ready for
general use: **on-device models**, **AI Knowledge / RAG**, and **Local
Semantic Search**.

When it is **off** (the default), every UI surface below is hidden.
Nothing is deleted: installed model files stay under `<filesDir>/`,
knowledge bases stay on disk, and **any KB already attached to a chat
or report keeps sending its context at API time** — only the
*entry-point UI* disappears. Flipping the toggle back on reveals
everything intact.

## Where the toggle lives

**Settings → UI tweaks → "Experimental features".** (The UI-tweaks
sub-screen is described on the Settings landing as "Model name layout,
full-screen, experimental features.")

Source of truth — the gate's own doc comment in
[`viewmodel/AppViewModel.kt`](../ai/src/main/java/com/ai/viewmodel/AppViewModel.kt)
(`GeneralSettings.experimentalFeaturesEnabled`):

> Master gate for experimental / advanced surfaces. When false, hides
> every UI surface related to on-device models (Local LLMs, LiteRT
> embedders, the synthetic `AppService.LOCAL` provider), AI Knowledge /
> RAG (Hub card, attach buttons in chat + report, share-target "Add to
> Knowledge" entry, Knowledge screens), and Local Semantic Search.
> Installed model files on disk stay put; flipping this back on reveals
> everything intact. KBs already attached to existing chats / reports
> keep sending context at API time even while the attach UI is hidden.

## Features hidden when the toggle is off

Each row below is a real gate check in the codebase (not a Compose
`@OptIn(Experimental…Api)` annotation, which is unrelated).

### On-device models (Local LLMs + LiteRT embedders)

| # | Surface | What's hidden | Gate site |
|---|---------|---------------|-----------|
| 1 | **AI Setup → AI Models → "Local Models" card** | The entry to the on-device LLM + LiteRT text-embedder setup screen (install / manage `.task` and `.tflite` models). | [`SetupScreens.kt:153`](../ai/src/main/java/com/ai/ui/settings/SetupScreens.kt) |
| 2 | **Chats hub → Local LLM chat card** | The dropdown card that picks an installed on-device LLM and jumps straight into a local chat session. Shown only when at least one `.task` LLM is installed. | [`ChatHub.kt:118`](../ai/src/main/java/com/ai/ui/chat/ChatHub.kt) |
| 3 | **Model pickers → synthetic `LOCAL` provider + local models** | The on-device models (and the synthetic `AppService.LOCAL` provider) stay invisible in every model picker even when `.task` / `.tflite` files exist on disk. The picker reads the prefs flag directly so it doesn't have to thread the flag through every caller. | [`Selection.kt:226-237`](../ai/src/main/java/com/ai/ui/other/Selection.kt) |

### AI Knowledge / RAG

| # | Surface | What's hidden | Gate site |
|---|---------|---------------|-----------|
| 4 | **Settings → UI tweaks → "Show AI Knowledge card on home page" toggle** | The secondary toggle itself is hidden (it only makes sense once Knowledge is enabled). It additionally gates surface #5. | [`SettingsScreen.kt:1264`](../ai/src/main/java/com/ai/ui/settings/SettingsScreen.kt) |
| 5 | **Hub → "AI Knowledge" card** | The home-screen entry into the Knowledge / RAG screens. Requires **both** `experimentalFeaturesEnabled` **and** `showKnowledgeCard`. | [`HubScreens.kt:177`](../ai/src/main/java/com/ai/ui/hub/HubScreens.kt) |
| 6 | **Chat composer → "📚 Knowledge" attach chip** | The per-chat KB attach chip (multi-select over saved KBs). Shown only when at least one KB exists. | [`ChatScreens.kt:652`](../ai/src/main/java/com/ai/ui/chat/ChatScreens.kt) |
| 7 | **New Report → "📚 Attach knowledge" button** | The report-start KB attach button (multi-select over saved KBs). Shown only when at least one KB exists. | [`SelectionPhase.kt:204`](../ai/src/main/java/com/ai/ui/report/start/SelectionPhase.kt) |
| 8 | **Share-target chooser → "Add to Knowledge" card** | The `ACTION_SEND` landing card that opens the Knowledge screen with the shared file / URL pre-staged. | [`ShareChooserScreen.kt:96`](../ai/src/main/java/com/ai/ui/share/ShareChooserScreen.kt) |

### Local Semantic Search

| # | Surface | What's hidden | Gate site |
|---|---------|---------------|-----------|
| 9 | **Search AI Reports → "📱 Local semantic search" item** | The on-device (embedder-backed) semantic search over reports. The other three search modes (Quick local, Extended local, Remote semantic) stay visible. | [`SearchAiReportsScreen.kt:65`](../ai/src/main/java/com/ai/ui/hub/SearchAiReportsScreen.kt) |

## What is *not* affected

- **Installed on-device model files** under `<filesDir>/local_llms/`
  and `<filesDir>/local_models/` — untouched (and also excluded from
  backup, see [backup-restore.md](backup-restore.md)).
- **Knowledge bases on disk** — KBs, sources, and embeddings persist.
- **KBs already attached to a chat or report** — they keep injecting
  context at API time even while the attach chips/buttons are hidden.
- **Remote semantic search** and the two local (text) search modes —
  always visible; only *Local semantic* search is gated.
- The flag round-trips through Import/Export
  ([`ImportExportScreen.kt`](../ai/src/main/java/com/ai/ui/settings/ImportExportScreen.kt),
  `experimentalFeaturesEnabled`).

## Related docs

- [local-runtime.md](local-runtime.md) — the on-device `LocalLlm` +
  `LocalEmbedder` runtime gated by surfaces 1-3.
- [knowledge.md](knowledge.md) — the RAG subsystem gated by surfaces 4-8.
- [help.md](help.md) — in-app Help; the toggle's own help card lives
  under the Settings-admin "UI tweaks" topic.
