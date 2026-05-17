package com.ai.ui.report

import com.ai.data.InternalPromptIconCache

/**
 * Icon resolution for the four export renderers (Complete HTML,
 * Short HTML / PDF, DOCX / ODT, Zipped HTML).
 *
 * Two semantic rules drive everything in here:
 *
 * 1. **Language / translation icons REPLACE the language name.** The
 *    `language*` helpers return either the icon (if cached) or a
 *    null / fallback the caller's text path picks up.
 * 2. **Every other icon ADDS to the existing label.** The `*Prefix`
 *    helpers return either `"<icon> "` (trailing space included) or
 *    the empty string — drop them in front of the existing text and
 *    the absent case becomes a no-op.
 *
 * Cache lookups mirror the in-app picker
 * ([com.ai.ui.report.LanguagePickerRow] with `useIcons = true`) so
 * exports and in-app rendering can't drift. When this module changes
 * its lookup logic, that picker should too.
 */

/** Resolved icon for [view]. Returns:
 *  - [sourceLanguageIcon] (= `Report.languageIcon`) when [view] is
 *    the Original tab, trimmed-blank → null.
 *  - The cached translation icon
 *    (`InternalPromptIconCache.get("translation_icon", displayName)`)
 *    for translation tabs, trimmed-blank → null.
 *  Null result means "no icon yet" — caller falls back to the
 *  language name text. */
internal fun languageIcon(view: HtmlLanguageView, sourceLanguageIcon: String?): String? {
    return if (view.key == LangTab.ORIGINAL_KEY) {
        sourceLanguageIcon?.takeIf { it.isNotBlank() }
    } else {
        InternalPromptIconCache.get("translation_icon", view.displayName)?.takeIf { it.isNotBlank() }
    }
}

/** Render-ready HTML label for [view]'s language. When an icon is
 *  cached the icon stands alone (no English name, no nativeName).
 *  When no icon is cached the caller-supplied [fallbackHtml] is
 *  emitted instead — typically `displayName` + an optional
 *  `<span class='lang-native'>nativeName</span>`, already escaped.
 *
 *  The icon itself is HTML-escaped here (defence in depth — emoji
 *  glyphs are safe today but the cache could theoretically grow
 *  multi-character entries some day). */
internal fun languageLabelOrIconHtml(
    view: HtmlLanguageView,
    sourceLanguageIcon: String?,
    fallbackHtml: String
): String {
    val icon = languageIcon(view, sourceLanguageIcon)
    return if (icon != null) escapeHtml(icon) else fallbackHtml
}

/** Plain-text equivalent of [languageLabelOrIconHtml] — for DOCX /
 *  ODT (no escaping needed) and `<title>` slots. */
internal fun languageLabelOrIconPlain(
    view: HtmlLanguageView,
    sourceLanguageIcon: String?,
    fallbackPlain: String
): String = languageIcon(view, sourceLanguageIcon) ?: fallbackPlain

/** Per-meta-prompt icon (additive). Uses
 *  [InternalPromptIconCache.getByName] — meta prompt names are
 *  reasonably unique so the title disambiguation isn't load-bearing
 *  here. Trimmed-blank → null. */
internal fun metaPromptIcon(name: String): String? =
    InternalPromptIconCache.getByName(name)?.takeIf { it.isNotBlank() }

/** Render-ready `"<icon> "` prefix for HTML output — emoji escaped,
 *  trailing space included. Empty string when [icon] is null/blank
 *  so callers can do `sb.append(iconPrefixHtml(a.icon))` with no
 *  conditional. */
internal fun iconPrefixHtml(icon: String?): String =
    if (icon.isNullOrBlank()) "" else "${escapeHtml(icon)} "

/** Plain-text prefix for DOCX / ODT block text. */
internal fun iconPrefixPlain(icon: String?): String =
    if (icon.isNullOrBlank()) "" else "$icon "

/** Local HTML-escape — the renderers each have their own private
 *  `esc` but importing them here would tangle the module graph.
 *  Same five-char escape set the renderers use. */
private fun escapeHtml(s: String): String {
    val out = StringBuilder(s.length + 8)
    for (c in s) when (c) {
        '&' -> out.append("&amp;")
        '<' -> out.append("&lt;")
        '>' -> out.append("&gt;")
        '"' -> out.append("&quot;")
        '\'' -> out.append("&#39;")
        else -> out.append(c)
    }
    return out.toString()
}
