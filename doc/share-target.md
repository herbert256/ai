# Share-Target

The app receives `ACTION_SEND` and `ACTION_SEND_MULTIPLE` intents
from any other app's share sheet, lets the user pick a destination
(Report, Chat, or Knowledge), and routes the payload accordingly.
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
vision-capable image shares; the explicit Office MIME types cover
the file-types the Knowledge ingest pipeline understands.

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
        onCancel = onSharedContentHandled,
        onSendToReport = { … },
        onSendToChat = { … },
        onSendToKnowledge = { … }
    )
    return
}
```

The chooser is a three-card screen showing a payload preview
(subject, text excerpt, attachment count, mime) plus three
destination cards. Cards disable themselves when the payload doesn't
fit (e.g. "New Chat" needs text; "Add to Knowledge" needs a file or
a URL).

## Three landing routes

### Report

`routeShareToReport(context, appViewModel, navController,
sharedContent)`:

1. Pre-fill `genericPromptTitle` and `genericPromptText` from the
   shared subject + text.
2. If exactly one image was attached, decode the bytes and base64
   them into `reportImageBase64` / `reportImageMime` so the
   Generate path treats it as a vision attachment.
3. Navigate to `AI_NEW_REPORT`.

### Chat

Stage `chatStarterText = sharedContent.text` in `UiState`, navigate
to `AI_CHAT_PROVIDER` (the configure-on-the-fly model picker so the
user picks model / params before chatting). `ChatSessionScreen`
consumes the staged text on first composition and clears it via
`onConsumeStarter()` so back-and-forward navigation doesn't
re-stuff the input box.

### Knowledge

Queue the URIs in `UiState.pendingKnowledgeUris` (URLs as strings,
file URIs as content:// strings); if the share was a single URL with
no file attached, queue the URL string instead. Navigate to
`AI_KNOWLEDGE`.

The Knowledge **list** screen (`KnowledgeListScreen`) shows a
sticky banner — "N shared items ready to import" — with a "Discard
share" button that clears the queue. The user picks an existing KB
or creates a new one; in either case the **detail** screen
(`KnowledgeDetailScreen`) auto-consumes the queue on first
composition:

- `http://` / `https://` URI → `KnowledgeService.indexUrl(...)`
- `content://` / `file://` URI → `pickTypeForUri` +
  `displayNameForUri` + `KnowledgeService.indexFile(...)`
- Status line cycles through "Reading…" / "Embedding…" / "Indexed"
  exactly the same as the manual + File / + Web page buttons.
- After the queue drains, `onConsumePending()` clears it so a
  back-and-forward doesn't re-import.

## Custom external intent — separate codepath

The older `com.ai.ACTION_NEW_REPORT` external-intent contract
(driven by `ExternalIntent` + `MainActivity.handleIntent`) is a
separate flow with its own 13-field payload. See the in-app **Help**
screen for the full contract; share-target handles only the
standard `ACTION_SEND` / `ACTION_SEND_MULTIPLE`.

## Files

- `ai/src/main/AndroidManifest.xml` — intent filters
- `ai/src/main/java/com/ai/MainActivity.kt` — extraction
- `ai/src/main/java/com/ai/data/SharedContent.kt` — snapshot
- `ai/src/main/java/com/ai/ui/share/ShareChooserScreen.kt` — picker
- `ai/src/main/java/com/ai/ui/navigation/AppNavHost.kt` — overlay
  and routing helpers (`routeShareToReport`, etc.)
- `ai/src/main/java/com/ai/ui/knowledge/KnowledgeScreens.kt` —
  `pendingUris` banner + auto-ingest
- `ai/src/main/java/com/ai/ui/chat/ChatScreens.kt` —
  `initialUserInput` consumption
