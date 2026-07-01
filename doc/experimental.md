# Experimental features — what the master toggle hides

The app carries a single master gate, **Experimental features**
(`GeneralSettings.experimentalFeaturesEnabled`, prefs key
`experimental_features`, **default `false`**). It is the on/off switch
for a cluster of in-progress / advanced surfaces that aren't ready for
general use: **on-device models**, **AI Knowledge / RAG**, and **Local
Semantic Search**.

When it is **off** (the default), the **nine** UI surfaces below are
hidden (three groups: on-device models, AI Knowledge / RAG, Local
Semantic Search). Nothing is deleted: installed model files stay under
`<filesDir>/`, knowledge bases stay on disk, and **any KB already
attached to a chat or report keeps sending its context at API time** —
only the *entry-point UI* disappears. Flipping the toggle back on
reveals everything intact.

The flag lives on `GeneralSettings`, persists to the main `eval_prefs`
SharedPreferences under key `experimental_features`, and round-trips
through Import/Export and the full backup zip.

## Where the toggle lives

**Settings → UI tweaks → "Experimental features".** (The UI-tweaks
sub-screen is reached from the Settings landing card whose subtitle
reads "Model name layout, full-screen, ladybug icons, experimental
features.") The toggle's own in-screen description is:

> Master gate for on-device Local LLMs, LiteRT embedders, AI Knowledge
> / RAG, and Local Semantic Search. Off (default) hides those UI
> surfaces — installed model files and KBs stay on disk, and any KB
> already attached to a chat or report keeps sending context at API
> time.

The "Show Knowledge card on home page" toggle (surface #4) is
rendered immediately below it, inside an `if (experimentalFeatures)`
block, so it only appears once the master gate is on.

Source of truth — the gate's own doc comment in
[`viewmodel/AppViewModelTypes.kt`](../ai/src/main/java/com/ai/viewmodel/AppViewModelTypes.kt)
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
| 1 | **AI Setup → AI Models → "Local Models" card** | The entry to the on-device LLM + LiteRT text-embedder setup screen (install / manage `.task` and `.tflite` models; subtitle "On-device LLMs and LiteRT text embedders", route `AI_LOCAL_MODELS_SETUP`). Gated by an `if (experimentalFeatures)` block inside `ModelsSetupScreen`. | [`SetupScreens.kt:193`](../ai/src/main/java/com/ai/ui/settings/SetupScreens.kt) |
| 2 | **Chats hub → Local LLM chat card** | The dropdown card that picks an installed on-device LLM and jumps straight into a local chat session. Gate is `experimentalFeatures && installedLocalLlms.isNotEmpty()` — shown only when at least one `.task` LLM is installed. | [`ChatHub.kt:122`](../ai/src/main/java/com/ai/ui/chat/ChatHub.kt) |
| 3 | **Model pickers → synthetic `LOCAL` provider + local models** | The on-device models (and the synthetic `AppService.LOCAL` provider, id `"Local"`) stay invisible in every model picker even when `.task` / `.tflite` files exist on disk. Unlike the other surfaces (which receive the flag as a parameter), this picker reads the prefs flag **directly** — `getSharedPreferences("eval_prefs", …).getBoolean("experimental_features", false)` — so it doesn't have to thread the flag through every caller. When off, `localModelsForFilter` is `emptyList()`, so `AppService.LOCAL` is never appended to the service list. | [`Selection.kt:246-261`](../ai/src/main/java/com/ai/ui/other/Selection.kt) |

### AI Knowledge / RAG

| # | Surface | What's hidden | Gate site |
|---|---------|---------------|-----------|
| 4 | **Settings → UI tweaks → "Show Knowledge card on home page" toggle** | The secondary toggle itself is hidden (it only makes sense once Knowledge is enabled), inside an `if (experimentalFeatures)` block directly below the master toggle in the UI-tweaks screen. The `showKnowledgeCard` field it controls additionally gates surface #5. | [`SettingsScreen.kt:1614`](../ai/src/main/java/com/ai/ui/settings/SettingsScreen.kt) |
| 5 | **Hub → "AI Knowledge" card** | The home-screen entry into the Knowledge / RAG screens. Requires **both** `experimentalFeaturesEnabled` **and** `showKnowledgeCard` (`if (… && …)`). | [`HubScreens.kt:153`](../ai/src/main/java/com/ai/ui/hub/HubScreens.kt) |
| 6 | **Chat composer → "📚 Knowledge" attach chip** | The per-chat KB attach chip (multi-select over saved KBs). Gate is `(experimentalFeatures && availableKbs.isNotEmpty()) \|\| attachedKnowledgeBaseIds.isNotEmpty()` — so it shows when the gate is on and a KB exists, **and also** (gate off or on) whenever a KB is already attached to this chat, where it renders the attached count so the user can still see / edit the attachment. | [`ChatScreens.kt:817`](../ai/src/main/java/com/ai/ui/chat/ChatScreens.kt) |
| 7 | **New Report → "📚 Attach knowledge" button** | The report-start KB attach button (multi-select over saved KBs). Gate is `experimentalFeatures && allKbs.isNotEmpty()` — shown only when at least one KB exists. | [`SelectionPhase.kt:205`](../ai/src/main/java/com/ai/ui/report/start/SelectionPhase.kt) |
| 8 | **Share-target chooser → "Add to Knowledge" card** | The `ACTION_SEND` landing card that opens the Knowledge screen with the shared file / URL pre-staged. Plain shared text that isn't a URL can't be ingested here and is steered to New Report instead. | [`ShareChooserScreen.kt:104`](../ai/src/main/java/com/ai/ui/share/ShareChooserScreen.kt) |

### Local Semantic Search

| # | Surface | What's hidden | Gate site |
|---|---------|---------------|-----------|
| 9 | **Search AI Reports → "Local semantic search" item** | The on-device (embedder-backed) semantic search over reports. The other three search modes — **Quick local search**, **Extended local search**, **Remote semantic search** — stay visible; only this on-device item sits behind `if (experimentalFeatures)`. | [`SearchAiReportsScreen.kt:63`](../ai/src/main/java/com/ai/ui/hub/SearchAiReportsScreen.kt) |

## What is *not* affected

- **Installed on-device model files** under `<filesDir>/local_llms/`
  and `<filesDir>/local_models/` — untouched (and also excluded from
  backup, see [backup-restore.md](backup-restore.md)).
- **Knowledge bases on disk** — KBs, sources, and embeddings persist.
- **KBs already attached to a chat or report** — they keep injecting
  context at API time even with the gate off. In **chat** the "📚
  Knowledge" chip even stays visible (showing the attached count) so
  you can still edit / remove the attachment (surface #6's OR clause);
  in **New Report** the attach button is hidden, but the attached KB
  still rides along on the report and injects at dispatch time.
- **Remote semantic search** and the two local (text) search modes —
  always visible; only *Local semantic* search is gated.
- The flag round-trips through Import/Export
  ([`ImportExportScreen.kt:362,483`](../ai/src/main/java/com/ai/ui/settings/ImportExportScreen.kt),
  serialized as `experimentalFeaturesEnabled`) and is backed up via
  `eval_prefs` in the full backup zip.

## Related docs

- [local-runtime.md](local-runtime.md) — the on-device `LocalLlm` +
  `LocalEmbedder` runtime gated by surfaces 1-3.
- [knowledge.md](knowledge.md) — the RAG subsystem gated by surfaces 4-8.
- [help.md](help.md) — in-app Help; the toggle's own help card lives
  under the Settings-admin "UI tweaks" topic.
