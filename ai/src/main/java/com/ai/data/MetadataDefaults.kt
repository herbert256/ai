package com.ai.data

// Factory defaults for the fallback emoji shown when a report (or a secondary
// result) carries no generated metadata icon of its own.
//
// These are the immutable "factory" values. The user can override any of them
// via Settings → Default icons; the live, possibly-overridden set is carried by
// [MetadataIcons] (provided to the composition through LocalMetadataIcons).
// View screens (ui/report/view/**) render these defaults whenever the report
// itself lacks an icon — they deliberately ignore the
// GeneralSettings.metadataEnabled master switch, so an older report keeps
// showing its real icons even after metadata generation is turned off, and a
// report that never had one always shows something sensible rather than a blank.
object MetadataDefaults {
    const val REPORT_ICON = "📝"
    const val MODEL_ICON = "🧠"
    const val RERANK = "🏆"
    const val MODERATE = "🚦"
    const val LANGUAGE = "🌐"
    const val TRANSLATE = "🌐"
    const val META = "🔗"
    const val FAN_OUT = "🔱"
    const val FAN_IN = "🎯"
}

// User-editable set of the default fallback emoji. Each field defaults to its
// [MetadataDefaults] factory constant; the whole set persists as one JSON blob
// in GeneralSettings and is edited on the Settings → Default icons screen.
data class MetadataIcons(
    val reportIcon: String = MetadataDefaults.REPORT_ICON,
    val reportModelIcon: String = MetadataDefaults.MODEL_ICON,
    val rerank: String = MetadataDefaults.RERANK,
    val moderate: String = MetadataDefaults.MODERATE,
    val languageIcon: String = MetadataDefaults.LANGUAGE,
    val translationRow: String = MetadataDefaults.TRANSLATE,
    val meta: String = MetadataDefaults.META,
    val fanOutRow: String = MetadataDefaults.FAN_OUT,
    val fanInRow: String = MetadataDefaults.FAN_IN,
) {
    // Configured glyph for a secondary-result row when its cached internal-prompt
    // icon is missing, keyed off the result's kind.
    fun forKind(kind: SecondaryKind): String = when (kind) {
        SecondaryKind.RERANK -> rerank
        SecondaryKind.MODERATION -> moderate
        SecondaryKind.TRANSLATE -> translationRow
        SecondaryKind.META -> meta
    }
}
