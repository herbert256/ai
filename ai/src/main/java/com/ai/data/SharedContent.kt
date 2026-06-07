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

    /** First http(s) URL embedded in [text], accepting browser shares
     *  that include a page title or markdown link instead of a bare URL. */
    val firstUrl: String? get() =
        text?.let { URL_REGEX.find(it)?.value?.trimEnd('.', ',', ';', ':', '!', '?') }

    /** True when [text] contains an http(s) URL. Used by the chooser to
     *  surface "Add to Knowledge as URL" only when applicable. */
    val isUrl: Boolean get() = firstUrl != null

    companion object {
        private val URL_REGEX = Regex("""https?://[^\s)\]>}"]+""", RegexOption.IGNORE_CASE)
    }
}
