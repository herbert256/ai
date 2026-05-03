package com.ai.data

/**
 * Snapshot of an Intent.ACTION_SEND / ACTION_SEND_MULTIPLE payload
 * the app received from another app. Held in MainActivity state and
 * threaded into AppNavHost which renders ShareChooserScreen on top
 * of whatever's already in the back-stack until the user picks a
 * destination (Report / Chat / Knowledge). One snapshot lifecycle:
 * receive intent → set → user picks → consume.
 *
 * - [text] is whatever was in EXTRA_TEXT (selected text, URLs, page
 *   excerpts). May be a URL or just prose.
 * - [subject] mirrors EXTRA_SUBJECT (browsers and mail apps put the
 *   page title / mail subject here). Used as a default report
 *   title or KB source name when present.
 * - [uris] holds EXTRA_STREAM (single) or the list from
 *   ACTION_SEND_MULTIPLE. Each entry is a `content://` URI with a
 *   read-grant from the share that we resolve through SAF.
 * - [mime] is Intent.type — "text/plain", "image/png",
 *   "application/pdf", etc. Drives type detection when the URI
 *   itself doesn't expose a useful filename.
 */
data class SharedContent(
    val text: String? = null,
    val subject: String? = null,
    val uris: List<String> = emptyList(),
    val mime: String? = null
) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && uris.isEmpty()

    /** True when [text] looks like a URL — single non-whitespace
     *  token starting with http(s)://. Used by the chooser to
     *  surface "Add to Knowledge as URL" only when applicable. */
    val isUrl: Boolean get() {
        val t = text?.trim() ?: return false
        if (t.contains("\\s".toRegex())) return false
        return t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)
    }
}
