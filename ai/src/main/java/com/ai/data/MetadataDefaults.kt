package com.ai.data

// Factory defaults for the fallback emoji shown when a report (or a secondary
// result) carries no generated metadata icon of its own, AND for every glyph
// the bottom action bars render.
//
// These are the immutable "factory" values. The user can override any of them
// via Settings → Default icons; the live, possibly-overridden set is carried by
// [MetadataIcons] (provided to the composition through LocalMetadataIcons).
// View screens (ui/report/view/**) render the report/secondary fallbacks
// whenever the report itself lacks an icon — they deliberately ignore the
// GeneralSettings.metadataEnabled master switch, so an older report keeps
// showing its real icons even after metadata generation is turned off, and a
// report that never had one always shows something sensible rather than a blank.
//
// The bottom-bar group below covers every action glyph the BottomIconBar /
// ViewBottomBar can draw. Each one has a matching [MetadataIcons] field so the
// glyph is editable on Settings → Default icons rather than hard-coded at the
// render site (see buildBottomBarIcons / ViewBottomBar).
object MetadataDefaults {
    // ---- Report / model fallback icons (view screens) ----
    const val REPORT_ICON = "📝"
    const val MODEL_ICON = "🧠"
    // ---- Secondary-result kinds ----
    const val RERANK = "🏆"
    const val MODERATE = "🚦"
    const val LANGUAGE = "🌐"
    const val TRANSLATE = "🌐"
    const val META = "🔗"
    const val FAN_OUT = "🔱"
    const val FAN_IN = "🎯"
    const val TOURNAMENT = "🥊"
    const val JUDGES = "⚖️"
    const val COMPARE = "🧮"

    // ---- Bottom action-bar icons ----
    // Monitor jump group.
    const val LIVE_DASHBOARD = "📡"
    const val TRACES = "🐞"
    const val APP_LOG = "📜"
    const val AUDIT = "🧾"
    const val STATISTICS_MONITOR = "📊"
    // Create / chat / per-response.
    const val ADD = "🆕"
    const val CHAT = "💬"
    const val AGENT_CHAT = "🗣️"
    const val TEMPERATURE_SWEEP = "🎲"
    const val REASONING_SWEEP = "🧠"
    const val WEB_SEARCH_REPLAY = "🧭"
    // Navigation jumps.
    const val PICK_REPORT = "🗂️"
    const val OPEN_MANAGE = "🔧"
    const val HOUSEKEEPING = "🧹"
    const val SETTINGS = "⚙️"
    const val STATISTICS = "📈"
    const val INFO = "ℹ️"
    // Configuration.
    const val PARAMETERS = "🌡️"
    const val SYSTEM_PROMPT = "🎭"
    const val CLEAR = "🧽"
    const val ATTACH = "📎"
    const val VALIDATE_PROMPT = "🚩"
    // Report-level actions.
    const val COPY = "📋"
    const val PIN = "📌"
    const val TOGGLE_LABELS = "🔤"
    const val SHARE = "📤"
    const val DUPLICATE = "👯"
    // Per-item actions.
    const val VIEW = "👁"
    const val TRANSLATION_COMPARE = "🌐"
    const val MEMO = "📝"
    const val ADD_NOTE = "✍️"
    const val LIST_NOTES = "📒"
    const val EDIT = "✏️"
    const val RELOAD = "🔄"
    const val DELETE = "🗑"
    // Help + View-bar "one vs all" toggle.
    const val HELP = "❓"
    const val HELP_LEGEND = "❔"
    const val VIEW_SHOW_ALL = "☝️"
    const val VIEW_SHOW_ONE = "✋"
}

// User-editable set of the default fallback / action emoji. Each field defaults
// to its [MetadataDefaults] factory constant; the whole set persists as one JSON
// blob in GeneralSettings and is edited on the Settings → Default icons screen.
data class MetadataIcons(
    // Report / model fallbacks (view screens).
    val reportIcon: String = MetadataDefaults.REPORT_ICON,
    val reportModelIcon: String = MetadataDefaults.MODEL_ICON,
    // Secondary-result kinds.
    val rerank: String = MetadataDefaults.RERANK,
    val moderate: String = MetadataDefaults.MODERATE,
    val languageIcon: String = MetadataDefaults.LANGUAGE,
    val translationRow: String = MetadataDefaults.TRANSLATE,
    val meta: String = MetadataDefaults.META,
    val fanOutRow: String = MetadataDefaults.FAN_OUT,
    val fanInRow: String = MetadataDefaults.FAN_IN,
    val tournament: String = MetadataDefaults.TOURNAMENT,
    val judges: String = MetadataDefaults.JUDGES,
    val compare: String = MetadataDefaults.COMPARE,
    // Bottom-bar: Monitor jump group.
    val liveDashboard: String = MetadataDefaults.LIVE_DASHBOARD,
    val traces: String = MetadataDefaults.TRACES,
    val appLog: String = MetadataDefaults.APP_LOG,
    val audit: String = MetadataDefaults.AUDIT,
    val statisticsMonitor: String = MetadataDefaults.STATISTICS_MONITOR,
    // Bottom-bar: create / chat / per-response.
    val add: String = MetadataDefaults.ADD,
    val chat: String = MetadataDefaults.CHAT,
    val agentChat: String = MetadataDefaults.AGENT_CHAT,
    val temperatureSweep: String = MetadataDefaults.TEMPERATURE_SWEEP,
    val reasoningSweep: String = MetadataDefaults.REASONING_SWEEP,
    val webSearchReplay: String = MetadataDefaults.WEB_SEARCH_REPLAY,
    // Bottom-bar: navigation jumps.
    val pickReport: String = MetadataDefaults.PICK_REPORT,
    val openManage: String = MetadataDefaults.OPEN_MANAGE,
    val housekeeping: String = MetadataDefaults.HOUSEKEEPING,
    val settings: String = MetadataDefaults.SETTINGS,
    val statistics: String = MetadataDefaults.STATISTICS,
    val info: String = MetadataDefaults.INFO,
    // Bottom-bar: configuration.
    val parameters: String = MetadataDefaults.PARAMETERS,
    val systemPrompt: String = MetadataDefaults.SYSTEM_PROMPT,
    val clear: String = MetadataDefaults.CLEAR,
    val attach: String = MetadataDefaults.ATTACH,
    val validatePrompt: String = MetadataDefaults.VALIDATE_PROMPT,
    // Bottom-bar: report-level actions.
    val copy: String = MetadataDefaults.COPY,
    val pin: String = MetadataDefaults.PIN,
    val toggleLabels: String = MetadataDefaults.TOGGLE_LABELS,
    val share: String = MetadataDefaults.SHARE,
    val duplicate: String = MetadataDefaults.DUPLICATE,
    // Bottom-bar: per-item actions.
    val view: String = MetadataDefaults.VIEW,
    val translationCompare: String = MetadataDefaults.TRANSLATION_COMPARE,
    val memo: String = MetadataDefaults.MEMO,
    val addNote: String = MetadataDefaults.ADD_NOTE,
    val listNotes: String = MetadataDefaults.LIST_NOTES,
    val edit: String = MetadataDefaults.EDIT,
    val reload: String = MetadataDefaults.RELOAD,
    val delete: String = MetadataDefaults.DELETE,
    // Help (❓ screen help, ❔ icons legend) + View-bar "one vs all" toggle.
    val help: String = MetadataDefaults.HELP,
    val helpLegend: String = MetadataDefaults.HELP_LEGEND,
    val viewShowAll: String = MetadataDefaults.VIEW_SHOW_ALL,
    val viewShowOne: String = MetadataDefaults.VIEW_SHOW_ONE,
) {
    // Configured glyph for a secondary-result row when its cached internal-prompt
    // icon is missing, keyed off the result's kind.
    fun forKind(kind: SecondaryKind): String = when (kind) {
        SecondaryKind.RERANK -> rerank
        SecondaryKind.MODERATION -> moderate
        SecondaryKind.TRANSLATE -> translationRow
        SecondaryKind.META -> meta
        SecondaryKind.TOURNAMENT -> tournament
        SecondaryKind.JUDGES -> judges
        SecondaryKind.COMPARE -> compare
    }

    // Backfill any field left null (older stored JSON predating it) or blank with
    // its factory default. Gson allocates the object without calling the Kotlin
    // constructor, so fields absent from the persisted JSON load as null rather
    // than picking up the constructor default — every load site runs this so the
    // bars never render a null/blank glyph. See SettingsPreferences / ImportExport.
    fun sanitized(): MetadataIcons {
        fun f(v: String?, d: String) = v?.takeIf { it.isNotBlank() } ?: d
        return MetadataIcons(
            reportIcon = f(reportIcon, MetadataDefaults.REPORT_ICON),
            reportModelIcon = f(reportModelIcon, MetadataDefaults.MODEL_ICON),
            rerank = f(rerank, MetadataDefaults.RERANK),
            moderate = f(moderate, MetadataDefaults.MODERATE),
            languageIcon = f(languageIcon, MetadataDefaults.LANGUAGE),
            translationRow = f(translationRow, MetadataDefaults.TRANSLATE),
            meta = f(meta, MetadataDefaults.META),
            fanOutRow = f(fanOutRow, MetadataDefaults.FAN_OUT),
            fanInRow = f(fanInRow, MetadataDefaults.FAN_IN),
            tournament = f(tournament, MetadataDefaults.TOURNAMENT),
            judges = f(judges, MetadataDefaults.JUDGES),
            compare = f(compare, MetadataDefaults.COMPARE),
            liveDashboard = f(liveDashboard, MetadataDefaults.LIVE_DASHBOARD),
            traces = f(traces, MetadataDefaults.TRACES),
            appLog = f(appLog, MetadataDefaults.APP_LOG),
            audit = f(audit, MetadataDefaults.AUDIT),
            statisticsMonitor = f(statisticsMonitor, MetadataDefaults.STATISTICS_MONITOR),
            add = f(add, MetadataDefaults.ADD),
            chat = f(chat, MetadataDefaults.CHAT),
            agentChat = f(agentChat, MetadataDefaults.AGENT_CHAT),
            temperatureSweep = f(temperatureSweep, MetadataDefaults.TEMPERATURE_SWEEP),
            reasoningSweep = f(reasoningSweep, MetadataDefaults.REASONING_SWEEP),
            webSearchReplay = f(webSearchReplay, MetadataDefaults.WEB_SEARCH_REPLAY),
            pickReport = f(pickReport, MetadataDefaults.PICK_REPORT),
            openManage = f(openManage, MetadataDefaults.OPEN_MANAGE),
            housekeeping = f(housekeeping, MetadataDefaults.HOUSEKEEPING),
            settings = f(settings, MetadataDefaults.SETTINGS),
            statistics = f(statistics, MetadataDefaults.STATISTICS),
            info = f(info, MetadataDefaults.INFO),
            parameters = f(parameters, MetadataDefaults.PARAMETERS),
            systemPrompt = f(systemPrompt, MetadataDefaults.SYSTEM_PROMPT),
            clear = f(clear, MetadataDefaults.CLEAR),
            attach = f(attach, MetadataDefaults.ATTACH),
            validatePrompt = f(validatePrompt, MetadataDefaults.VALIDATE_PROMPT),
            copy = f(copy, MetadataDefaults.COPY),
            pin = f(pin, MetadataDefaults.PIN),
            toggleLabels = f(toggleLabels, MetadataDefaults.TOGGLE_LABELS),
            share = f(share, MetadataDefaults.SHARE),
            duplicate = f(duplicate, MetadataDefaults.DUPLICATE),
            view = f(view, MetadataDefaults.VIEW),
            translationCompare = f(translationCompare, MetadataDefaults.TRANSLATION_COMPARE),
            memo = f(memo, MetadataDefaults.MEMO),
            addNote = f(addNote, MetadataDefaults.ADD_NOTE),
            listNotes = f(listNotes, MetadataDefaults.LIST_NOTES),
            edit = f(edit, MetadataDefaults.EDIT),
            reload = f(reload, MetadataDefaults.RELOAD),
            delete = f(delete, MetadataDefaults.DELETE),
            help = f(help, MetadataDefaults.HELP),
            helpLegend = f(helpLegend, MetadataDefaults.HELP_LEGEND),
            viewShowAll = f(viewShowAll, MetadataDefaults.VIEW_SHOW_ALL),
            viewShowOne = f(viewShowOne, MetadataDefaults.VIEW_SHOW_ONE),
        )
    }
}
