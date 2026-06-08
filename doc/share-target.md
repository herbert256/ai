# Share-Target

The app is a standard Android share-sheet target. It receives
`ACTION_SEND` and `ACTION_SEND_MULTIPLE` intents from any other app,
shows a chooser that lets the user pick a destination (**Report**,
**Chat**, or — only when Experimental features is on — **Knowledge**),
and routes the payload accordingly.

Code lives in:

- `MainActivity` — extracts the intent into a `SharedContent`
  snapshot.
- `data/SharedContent.kt` — the snapshot data class.
- `ui/share/ShareChooserScreen.kt` — the destination picker.
- `ui/navigation/AppNavHost.kt` — renders the chooser as an overlay
  and holds the three routing helpers.
- `ui/chat/ChatScreens.kt` — consumes the staged chat starter text.

> A second, unrelated entry point — the exported
> `com.ai.ACTION_NEW_REPORT` custom intent — also lands in
> `MainActivity.handleIntent`, but it is a different contract with its
> own confirmation flow. See [Custom external intent](#custom-external-intent--separate-codepath)
> at the bottom.

## Manifest

`AndroidManifest.xml` registers `MainActivity` for `ACTION_SEND` over
three mimetype buckets and `ACTION_SEND_MULTIPLE` over two:

```xml
<!-- SEND -->
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="text/*" />
</intent-filter>
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="image/*" />
</intent-filter>
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="application/pdf" />
    <data android:mimeType="application/vnd.openxmlformats-officedocument.wordprocessingml.document" />
    <data android:mimeType="application/vnd.oasis.opendocument.text" />
    <data android:mimeType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" />
    <data android:mimeType="application/vnd.oasis.opendocument.spreadsheet" />
</intent-filter>

<!-- SEND_MULTIPLE — image/* and the document mimetypes ONLY -->
<intent-filter>
    <action android:name="android.intent.action.SEND_MULTIPLE" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="image/*" />
</intent-filter>
<intent-filter>
    <action android:name="android.intent.action.SEND_MULTIPLE" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="application/pdf" />
    <data android:mimeType="application/vnd.openxmlformats-officedocument.wordprocessingml.document" />
    <data android:mimeType="application/vnd.oasis.opendocument.text" />
    <data android:mimeType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" />
    <data android:mimeType="application/vnd.oasis.opendocument.spreadsheet" />
</intent-filter>
```

Notes on the exact filter set:

- `text/*` covers selected text and shared URLs — but **only as a
  single `SEND`**. There is deliberately no `text/*` `SEND_MULTIPLE`
  filter; multi-share is for files (images + the five document types),
  not text snippets.
- `image/*` covers vision-capable image shares, single or multiple.
- The five document mimetypes are PDF, DOCX, ODT, XLSX, ODS — exactly
  the file types the Knowledge extractors can ingest (the Report
  vision path only uses the first *image*). They are the file payloads
  the chooser can hand to Report-as-knowledge or to the Knowledge
  screen.

`MainActivity` is `launchMode="singleTop"` and `exported="true"`. The
`MAIN`/`LAUNCHER` and `com.ai.ACTION_NEW_REPORT` filters share the same
activity.

## Snapshot

`MainActivity.handleIntent` extracts the payload into a `SharedContent`
data class (`data/SharedContent.kt`), held as
`mutableStateOf<SharedContent?>` on the activity:

```kotlin
data class SharedContent(
    val text: String? = null,             // EXTRA_TEXT
    val subject: String? = null,          // EXTRA_SUBJECT
    val uris: List<String> = emptyList(), // EXTRA_STREAM (single or list) as Uri strings
    val mime: String? = null              // intent.type
) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && uris.isEmpty()

    /** First http(s) URL *embedded anywhere* in [text] (a regex match,
     *  trailing punctuation trimmed). Accepts browser shares that wrap
     *  the URL in a page title or markdown link, not just a bare URL. */
    val firstUrl: String?

    /** True when [text] contains an http(s) URL (`firstUrl != null`) —
     *  drives the "Add to Knowledge as URL" card and queue entry. */
    val isUrl: Boolean
}
```

> `isUrl` is no longer a "the whole text *is* a URL" test — it now
> fires whenever a URL appears anywhere in the text, and `firstUrl`
> exposes just the matched URL so the Knowledge route can queue the
> link rather than the surrounding prose.

- `ACTION_SEND` reads a single `EXTRA_STREAM` Uri into a one-element
  `uris` list (`listOfNotNull(uri?.toString())`).
- `ACTION_SEND_MULTIPLE` reads the Uri `ArrayList` and maps each to a
  string.
- Both branches also pull `EXTRA_TEXT`, `EXTRA_SUBJECT`, and
  `intent.type`.

Uri extraction is API-version-aware: `getParcelableExtra(key,
Uri::class.java)` / `getParcelableArrayListExtra(key, Uri::class.java)`
on API 33+, falling back to the deprecated unchecked cast on older
devices.

The launch intent is only processed when `savedInstanceState == null`,
so a configuration change (rotation, locale switch) does **not**
re-import shared content the user already consumed — otherwise the
chooser or chat composer would re-populate with a payload that was
just dismissed.

## Chooser overlay

`AppNavHost` renders `ShareChooserScreen` as an **overlay before the
NavHost** whenever there is a non-empty share to handle:

```kotlin
if (sharedContent != null && !sharedContent.isEmpty) {
    ShareChooserScreen(
        shared = sharedContent,
        experimentalFeatures = uiState.generalSettings.experimentalFeaturesEnabled,
        onCancel = onSharedContentHandled,
        onSendToReport   = { /* routeShareToReport(...) */ },
        onSendToChat     = { /* routeShareToChat(...) — text + first image */ },
        onSendToKnowledge = { /* stage pendingKnowledgeUris, nav AI_KNOWLEDGE */ }
    )
    return
}
```

The overlay uses the established full-screen-overlay `return` idiom:
because the `return` sits above the `NavHost`, the chooser layers on
top of whatever is in the back-stack and the back-stack's `remember`
state survives. A `BackHandler` inside `ShareChooserScreen` routes the
hardware back button to `onCancel`, which clears the share state
(`onSharedContentHandled`) and reveals the screen underneath.

`TitleBar` on the chooser uses `title = "Share"` (subtitle "Turn shared
content into a report/chat") with the dedicated help topic
`share_target` (defined in `DeveloperHelp.kt`).

The chooser shows a preview card — subject (bold), up to 300 chars of
text (truncated with an ellipsis), an attachment count ("1 attachment" /
"N attachments"), and the mime type — then up to three destination
cards:

| Card | Icon | Enabled when | Notes |
|------|------|--------------|-------|
| **New Report** | report glyph | `hasText \|\| hasUris` | Multi-model analysis; text → prompt, first image → vision, other files → KB. |
| **New Chat** | chat glyph | `hasText \|\| hasImageUris` | Single-model conversation; text + first image staged as the first turn. |
| **Add to Knowledge** | library glyph | `hasUris \|\| isUrl` | Only rendered when `experimentalFeatures` is on. |

`hasImageUris` is true when any shared Uri resolves to an `image/*` type
(`contentResolver.getType`, falling back to `shared.mime`) — so a pure
image share with no text can still go to Chat (the image becomes the
first turn's attachment).

The "Add to Knowledge" card is shown only when Experimental features is
enabled — Knowledge / RAG is an experimental surface. With it off, only
Report and Chat appear; sharing still works.

## Three landing routes

### Report — `routeShareToReport`

`routeShareToReport(context, appViewModel, navController, shared)` is a
suspend helper:

1. Title comes from the shared subject, prompt from the shared text
   (both blank-trimmed to `""`).
2. Attachment URIs are partitioned by mime (`contentResolver.getType`,
   falling back to `shared.mime`):
   - The **first image-typed** Uri is decoded off the main thread via
     `loadImageAsBase64` (`data/ImageAttach.kt`): bounds-only first
     pass picks an `inSampleSize`, the bitmap is scaled to a 1568 px
     long edge and re-encoded as JPEG (quality 85). The resulting
     `(mime, base64)` is staged into `reportImageBase64` /
     `reportImageMime`, where the mime is always `image/jpeg`. This is
     the report's vision attachment.
   - **Non-image** Uris (PDF / DOCX / etc.) are staged into
     `pendingReportKnowledgeUris`. A report has no per-report file
     home, so the New Report screen surfaces a banner offering to
     auto-create a one-shot knowledge base from those files and attach
     it — rather than silently dropping the docs.
3. Navigate to the New Report editor via
   `aiNewReportWithParams(title, prompt)` (route `AI_NEW_REPORT_WITH_PARAMS`),
   `popUpTo(AI)`. The user still picks models and taps Generate — no
   API credits move automatically.

### Chat — `routeShareToChat`

`routeShareToChat(context, appViewModel, navController, shared)` is a
suspend helper:

1. Stage `chatStarterText = shared.text` in `UiState`.
2. The **first image-typed** Uri (`contentResolver.getType`, falling
   back to `shared.mime`) is decoded off the main thread via
   `loadImageAsBase64` and staged into `chatStarterImageBase64` /
   `chatStarterImageMime` (same 1568 px / JPEG-85 downscale as the
   Report path; mime always `image/jpeg`). So a share to Chat carries
   the text **and** the first image as the first turn's vision
   attachment. These are the same two UiState fields the AI Chat hub's
   "📸 Start with photo" entry writes — the share chooser is no longer
   text-only. Non-image attachments are ignored on the chat route.
3. Navigate to `AI_CHAT_PROVIDER` — the configure-on-the-fly provider
   picker — so the user chooses model / parameters before chatting,
   `popUpTo(AI)`.

`ChatSessionScreen` (wired from `ChatRoutes.kt`) receives the staged
text as `initialUserInput` and the staged image as
`initialUserImageBase64` / `initialUserImageMime`, seeds the input box
and the attached-image slot on first composition, and fires
`onConsumeStarter()` (clearing all three UiState fields) so navigating
away and back doesn't re-stuff them. A `rememberSaveable` consumed-flag
plus `rememberSaveable` input state mean a rotation or process
recreation between staging the starter and tapping Send keeps the
user's text and image.

### Knowledge

Handled inline in `AppNavHost.onSendToKnowledge` (only reachable when
the Knowledge card was shown, i.e. Experimental features on):

1. Build **one** queue from the attachment URIs plus the extracted URL
   (`shared.firstUrl`, when one matched):
   `shared.uris + listOfNotNull(shared.firstUrl.orEmpty().takeIf { it.isNotBlank() })`.
   Only the matched URL is queued, not the surrounding prose — a
   browser share like "Some page title https://example.com" hands
   Knowledge just the URL.
2. Stage it into `pendingKnowledgeUris` and navigate to `AI_KNOWLEDGE`,
   `popUpTo(AI)`.

The Knowledge screen drains the queue and branches per entry on
`content://` (file import) vs `http(s)://` (URL ingest). Merging both
into a single queue means a share carrying *both* a URL and a file no
longer drops the URL — the earlier flow wrote the Uri list, then
conditionally overwrote it with a URL-only list only when the Uri list
was empty. See [knowledge.md](knowledge.md) /
[experimental.md](experimental.md).

## Custom external intent — separate codepath

The exported `com.ai.ACTION_NEW_REPORT` intent is a distinct contract
handled by `MainActivity.handleIntent` (it stages `externalTitle /
externalSystem / externalPrompt / externalInstructions`) and processed
in `AppNavHost`. It is **not** part of the share-sheet flow.

Three behaviours, by how much the intent asks for:

- **Bare prompt** (no `<instructions>` block and no `-- end prompt --`
  marker) → merely pre-fills the New Report editor via
  `aiNewReportWithParams`. The user still picks models and taps
  Generate, so no credits move without consent.
- **Prompt + instructions** (an `externalInstructions` extra, or a
  `-- end prompt --` marker splitting prompt from instructions) → the
  instructions are parsed into a `PendingExternalReport` (a 15-field
  payload: `title`, `systemPrompt`, `aiPrompt`, `openHtml`,
  `closeHtml`, `reportType`, `email`, `nextAction`, `hasReturn`,
  `hasEdit`, `hasSelect`, `agentNames`, `flockNames`, `swarmNames`,
  `modelSpecs`, extracted from `<open>`, `<close>`, `<type>`,
  `<email>`, `<next>`, `<return>`, `<edit>`, `<select>`, `<agent>`,
  `<flock>`, `<swarm>`, `<model>` tags) and an
  `ExternalIntentConfirmScreen` is shown first.

The confirmation overlay (help topic `external_intent`, title
"External request") lays out exactly what will happen — which models
get called, which side effects fire (`<email>`, `<next>`, return-on-
completion), and a prompt preview — with **Cancel** and a confirm
button labelled **Generate** when the intent would auto-generate
(`willAutoGenerate` = no `<edit>`/`<select>`, a `<type>`, and at least
one model source) or **Continue** otherwise. This explicit consent
step matches the app's "no background billing without acknowledgement"
posture; previously such intents could run silently and mask surprise
spend. On confirm with `<edit>`, the user lands in the New Report
editor; otherwise the agent-selection / generation flow runs.

The launch intent is staged once (`savedInstanceState == null`) and the
source extras are cleared after staging so a configuration change can't
re-stage the confirmation after the user has cancelled or confirmed.

## Files

- `ai/src/main/AndroidManifest.xml` — intent filters (SEND ×3,
  SEND_MULTIPLE ×2).
- `ai/src/main/java/com/ai/MainActivity.kt` — `handleIntent`,
  API-aware Uri extraction, fresh-start guard.
- `ai/src/main/java/com/ai/data/SharedContent.kt` — the snapshot data
  class (`isEmpty`, `isUrl`).
- `ai/src/main/java/com/ai/ui/share/ShareChooserScreen.kt` — the
  three-card picker.
- `ai/src/main/java/com/ai/ui/share/ExternalIntentConfirmScreen.kt` —
  `PendingExternalReport` + the custom-intent confirmation overlay.
- `ai/src/main/java/com/ai/ui/navigation/AppNavHost.kt` — the chooser
  overlay, `routeShareToReport`, `routeShareToChat`, and the inline
  knowledge routing.
- `ai/src/main/java/com/ai/data/ImageAttach.kt` — `loadImageAsBase64`
  (downscale + JPEG re-encode for the vision attachment; shared by the
  Report and Chat routes).
- `ai/src/main/java/com/ai/ui/navigation/ChatRoutes.kt` — wires the
  staged `chatStarterText` / `chatStarterImageBase64` /
  `chatStarterImageMime` UiState fields into `ChatSessionScreen`.
- `ai/src/main/java/com/ai/ui/chat/ChatScreens.kt` —
  `initialUserInput` / `initialUserImageBase64` / `initialUserImageMime`
  / `onConsumeStarter` consumption.
