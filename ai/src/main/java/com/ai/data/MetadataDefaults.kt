package com.ai.data

// Single source of truth for the fallback emoji shown when a report (or a
// secondary result) carries no generated metadata icon of its own.
//
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
    const val TRANSLATE = "🌐"
    const val META = "🔗"
    const val FAN_OUT = "🔱"
    const val FAN_IN = "🎯"

    // Default glyph for a secondary-result row when its cached internal-prompt
    // icon is missing, keyed off the result's kind.
    fun forKind(kind: SecondaryKind): String = when (kind) {
        SecondaryKind.RERANK -> RERANK
        SecondaryKind.MODERATION -> MODERATE
        SecondaryKind.TRANSLATE -> TRANSLATE
        SecondaryKind.META -> META
    }
}
