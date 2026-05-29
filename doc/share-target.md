# Share-Target

The app receives `ACTION_SEND` and `ACTION_SEND_MULTIPLE` intents
from any other app's share sheet, lets the user pick a destination
(Report, Chat, or — when Experimental features is on — Knowledge),
and routes the payload accordingly.
Code lives in `MainActivity`, `data/SharedContent.kt`,
`ui/share/ShareChooserScreen.kt`, and `ui/navigation/AppNavHost.kt`.

## Manifest

`AndroidManifest.xml` registers the activity for:

```xml
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
<!-- + matching SEND_MULTIPLE filters -->
```

`text/*` covers selected text and shared URLs; `image/*` covers
vision-capable image shares.

## Snapshot

`MainActivity.handleIntent` extracts the payload into a
`SharedContent` data class (in `data/SharedContent.kt`) held as
`mutableStateOf<SharedContent?>`:

```kotlin
data class SharedContent(
    val text: String? = null,            // EXTRA_TEXT
    val subject: String? = null,         // EXTRA_SUBJECT
    val uris: List<String> = emptyList(),// EXTRA_STREAM (single or multiple) as Uri strings
    val mime: String? = null             // intent.type
) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && uris.isEmpty()
    val isUrl: Boolean
}
```

API-version-aware Uri extraction uses `getParcelableExtra(key,
Uri::class.java)` on API 33+ and falls back to the deprecated cast
on older devices.

## Chooser overlay

`AppNavHost` renders `ShareChooserScreen` as an **overlay before the
NavHost** when `sharedContent != null && !sharedContent.isEmpty`:

```kotlin
if (sharedContent != null && !sharedContent.isEmpty) {
    ShareChooserScreen(
        shared = sharedContent,
        experimentalFeatures = …,
        onCancel = onSharedContentHandled,
        onSendToReport = { … },
        onSendToChat = { … },
        onSendToKnowledge = { … }
    )
    return
}
```

The chooser shows a payload preview (subject, text excerpt,
attachment count, mime) plus up to three destination cards. Cards
disable themselves when the payload doesn't fit (e.g. "New Chat"
needs text; "Add to Knowledge" needs attachment URIs or a URL). The
"Add to Knowledge" card is shown only when `experimentalFeatures` is
on — Knowledge / RAG is experimental.

The overlay's open / closed state is preserved across nav so a
back-press from a deep destination (e.g. a captured trace's detail)
returns to the chooser, not to the bare home screen.

## Three landing routes

### Report

`routeShareToReport(context, appViewModel, navController,
sharedContent)`:

1. Pre-fill `genericPromptTitle` and `genericPromptText` from the
   shared subject + text.
2. If exactly one image was attached, decode the bytes (off the
   main thread), downscale + JPEG-encode, and base64 the result
   into `reportImageBase64` / `reportImageMime` so the Generate
   path treats it as a vision attachment.
3. Non-image attachment URIs (PDF / DOCX / etc.) are staged into
   `pendingReportKnowledgeUris` — a report has no per-report file
   home, so the user is nudged to send the same payload to Knowledge
   instead of the docs being silently dropped.
4. Navigate to `AI_NEW_REPORT`.

### Chat

Stage `chatStarterText = sharedContent.text` in `UiState`,
optionally stage `chatStarterImageBase64/Mime`, navigate to
`AI_CHAT_PROVIDER` (the configure-on-the-fly model picker so the
user picks model / params before chatting). `ChatSessionScreen`
consumes the staged text on first composition and clears it via
`onConsumeStarter()` so back-and-forward navigation doesn't
re-stuff the input box. Staged image / text persist across process
recreation.

### Knowledge

`onSendToKnowledge` builds a single queue from the attachment URIs
plus the URL text (when `sharedContent.isUrl`), stages it into
`pendingKnowledgeUris` in `UiState`, and navigates to `AI_KNOWLEDGE`.
The Knowledge screen consumes the queue and branches per entry on
`content://` (file import) vs `http(s)://` (URL ingest). Merging
both into one queue means a share carrying *both* a URL and a file
no longer drops the URL. This card only appears when Experimental
features is enabled (see [knowledge.md](knowledge.md) /
[experimental.md](experimental.md)).

## Custom external intent — separate codepath

The older `com.ai.ACTION_NEW_REPORT` external-intent contract
(driven by `ExternalIntent` + `MainActivity.handleIntent`) is a
separate flow with its own 13-field payload. The user **must
confirm** before the cross-app launch fires the API calls — a
short confirmation screen surfaces the prompt and the selected
models, with Generate and Cancel buttons. Previously the report
ran silently, which masked surprise spend; the explicit consent
step matches the rest of the app's "no background billing
without acknowledgement" posture. See the in-app **Help** screen
for the full intent contract; share-target handles only the
standard `ACTION_SEND` / `ACTION_SEND_MULTIPLE`.

## Files

- `ai/src/main/AndroidManifest.xml` — intent filters
- `ai/src/main/java/com/ai/MainActivity.kt` — extraction
- `ai/src/main/java/com/ai/data/SharedContent.kt` — snapshot
- `ai/src/main/java/com/ai/ui/share/ShareChooserScreen.kt` — picker
- `ai/src/main/java/com/ai/ui/navigation/AppNavHost.kt` — overlay
  and routing helpers (`routeShareToReport`, etc.)
- `ai/src/main/java/com/ai/ui/chat/ChatScreens.kt` —
  `initialUserInput` consumption
