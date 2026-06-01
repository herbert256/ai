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
    // ---- Other UI icons (status, marks, arrows, objects) ----
    // Status & progress
    const val STATUS_DONE = "✅"
    const val STATUS_FAILED = "❌"
    const val STATUS_PENDING = "⏳"
    const val STATUS_PAUSED = "⏸"
    const val STATUS_STOPPED = "⏹"
    const val STATUS_ALARM = "⏰"
    const val STATUS_BLOCKED = "🚫"
    const val STATUS_LOCKED = "🔒"
    const val STATUS_WARNING = "⚠️"
    const val HOT = "🔥"
    const val CLOCK_TIME = "🕒"
    const val CLOCK_QUEUED = "🕓"
    const val CLOCK_RECENT = "🕘"
    // Marks & ranks
    const val CHECK = "✓"
    const val CROSS = "✗"
    const val CLOSE = "✕"
    const val CHECKBOX_ON = "☑"
    const val CHECKBOX_OFF = "☐"
    const val BOX_BLANK = "⬜"
    const val MEDAL_GOLD = "🥇"
    const val MEDAL_SILVER = "🥈"
    const val MEDAL_BRONZE = "🥉"
    // Arrows
    const val ARROW_RIGHT = "→"
    const val ARROW_DOWN = "↓"
    const val ARROW_SUBMIT = "➤"
    // Search & files
    const val AGENT = "🤖"
    const val AI_FIND = "🤖"            // 🤖 AI-icon-finder affordance (split from AGENT)
    const val WEB = "🌐"               // 🌐 web / remote / network (split from TRANSLATE)
    const val LOOKUP = "🔎"
    const val SEARCH = "🔍"
    const val FOLDER_OPEN = "📂"
    const val LABEL = "🏷️"
    const val BOOKMARK = "🔖"
    const val NOTEPAD = "🗒"
    const val PACKAGE_BOX = "📦"
    // Content & media
    const val WORLD = "🌍"
    const val CHART = "📊"             // 📊 generic statistics / chart (split from STATISTICS_MONITOR)
    const val CYCLONE = "🌀"
    const val LIBRARY = "📚"
    const val BOOK = "📖"
    const val IMAGE = "🖼️"
    const val MAIL = "✉️"
    const val SPEECH = "🗨️"
    const val GEM = "💎"
    const val TIP = "💡"
    // Cost
    const val COST = "💰"
    const val DOLLAR = "💲"
    const val SPEND = "💸"
    // Workers & tools
    const val SWARM = "🐝"
    const val FLOCK = "🦆"
    const val FAN_IN_KNOT = "🪢"
    const val FEATHER = "🪶"
    const val TOOLS = "🛠️"
    const val TOOLBOX = "🧰"
    const val PUZZLE = "🧩"
    const val PALETTE = "🎨"
    const val TEST = "🧪"
    const val WORKER = "👷"
    const val SPARKLES = "✨"
    const val RULER = "📐"
    const val SHUFFLE = "🔀"
    const val HIDE = "🙈"
    // Devices & misc
    const val DEVICE = "📱"
    const val COMPUTER = "💻"
    const val SATELLITE = "🛰"
    const val HUGGINGFACE = "🤗"
}

// Holder for the live [MetadataIcons] so non-@Composable call sites (helper
// functions, when-expressions returning a glyph, data construction) can read the
// user's Default icons without a composition context. Kept in sync by AppNavHost
// (SideEffect at the LocalMetadataIcons provider). @Composable call sites should
// prefer LocalMetadataIcons.current so they recompose on change; this is the
// fallback for everything else. Defaults to the factory set until first synced.
object MetadataIconsHolder {
    @Volatile
    var current: MetadataIcons = MetadataIcons()
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
    // Other UI icons.
    // Status & progress
    val statusDone: String = MetadataDefaults.STATUS_DONE,
    val statusFailed: String = MetadataDefaults.STATUS_FAILED,
    val statusPending: String = MetadataDefaults.STATUS_PENDING,
    val statusPaused: String = MetadataDefaults.STATUS_PAUSED,
    val statusStopped: String = MetadataDefaults.STATUS_STOPPED,
    val statusAlarm: String = MetadataDefaults.STATUS_ALARM,
    val statusBlocked: String = MetadataDefaults.STATUS_BLOCKED,
    val statusLocked: String = MetadataDefaults.STATUS_LOCKED,
    val statusWarning: String = MetadataDefaults.STATUS_WARNING,
    val hot: String = MetadataDefaults.HOT,
    val clockTime: String = MetadataDefaults.CLOCK_TIME,
    val clockQueued: String = MetadataDefaults.CLOCK_QUEUED,
    val clockRecent: String = MetadataDefaults.CLOCK_RECENT,
    // Marks & ranks
    val checkMark: String = MetadataDefaults.CHECK,
    val crossMark: String = MetadataDefaults.CROSS,
    val closeMark: String = MetadataDefaults.CLOSE,
    val checkboxOn: String = MetadataDefaults.CHECKBOX_ON,
    val checkboxOff: String = MetadataDefaults.CHECKBOX_OFF,
    val boxBlank: String = MetadataDefaults.BOX_BLANK,
    val medalGold: String = MetadataDefaults.MEDAL_GOLD,
    val medalSilver: String = MetadataDefaults.MEDAL_SILVER,
    val medalBronze: String = MetadataDefaults.MEDAL_BRONZE,
    // Arrows
    val arrowRight: String = MetadataDefaults.ARROW_RIGHT,
    val arrowDown: String = MetadataDefaults.ARROW_DOWN,
    val arrowSubmit: String = MetadataDefaults.ARROW_SUBMIT,
    // Search & files
    val agent: String = MetadataDefaults.AGENT,
    val aiFind: String = MetadataDefaults.AI_FIND,
    val web: String = MetadataDefaults.WEB,
    val lookup: String = MetadataDefaults.LOOKUP,
    val search: String = MetadataDefaults.SEARCH,
    val folderOpen: String = MetadataDefaults.FOLDER_OPEN,
    val label: String = MetadataDefaults.LABEL,
    val bookmark: String = MetadataDefaults.BOOKMARK,
    val notepad: String = MetadataDefaults.NOTEPAD,
    val packageBox: String = MetadataDefaults.PACKAGE_BOX,
    // Content & media
    val world: String = MetadataDefaults.WORLD,
    val chart: String = MetadataDefaults.CHART,
    val cyclone: String = MetadataDefaults.CYCLONE,
    val library: String = MetadataDefaults.LIBRARY,
    val book: String = MetadataDefaults.BOOK,
    val image: String = MetadataDefaults.IMAGE,
    val mail: String = MetadataDefaults.MAIL,
    val speech: String = MetadataDefaults.SPEECH,
    val gem: String = MetadataDefaults.GEM,
    val tip: String = MetadataDefaults.TIP,
    // Cost
    val cost: String = MetadataDefaults.COST,
    val dollar: String = MetadataDefaults.DOLLAR,
    val spend: String = MetadataDefaults.SPEND,
    // Workers & tools
    val swarm: String = MetadataDefaults.SWARM,
    val flock: String = MetadataDefaults.FLOCK,
    val fanInKnot: String = MetadataDefaults.FAN_IN_KNOT,
    val feather: String = MetadataDefaults.FEATHER,
    val tools: String = MetadataDefaults.TOOLS,
    val toolbox: String = MetadataDefaults.TOOLBOX,
    val puzzle: String = MetadataDefaults.PUZZLE,
    val palette: String = MetadataDefaults.PALETTE,
    val test: String = MetadataDefaults.TEST,
    val worker: String = MetadataDefaults.WORKER,
    val sparkles: String = MetadataDefaults.SPARKLES,
    val ruler: String = MetadataDefaults.RULER,
    val shuffle: String = MetadataDefaults.SHUFFLE,
    val hide: String = MetadataDefaults.HIDE,
    // Devices & misc
    val device: String = MetadataDefaults.DEVICE,
    val computer: String = MetadataDefaults.COMPUTER,
    val satellite: String = MetadataDefaults.SATELLITE,
    val huggingface: String = MetadataDefaults.HUGGINGFACE,
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
            statusDone = f(statusDone, MetadataDefaults.STATUS_DONE),
            statusFailed = f(statusFailed, MetadataDefaults.STATUS_FAILED),
            statusPending = f(statusPending, MetadataDefaults.STATUS_PENDING),
            statusPaused = f(statusPaused, MetadataDefaults.STATUS_PAUSED),
            statusStopped = f(statusStopped, MetadataDefaults.STATUS_STOPPED),
            statusAlarm = f(statusAlarm, MetadataDefaults.STATUS_ALARM),
            statusBlocked = f(statusBlocked, MetadataDefaults.STATUS_BLOCKED),
            statusLocked = f(statusLocked, MetadataDefaults.STATUS_LOCKED),
            statusWarning = f(statusWarning, MetadataDefaults.STATUS_WARNING),
            hot = f(hot, MetadataDefaults.HOT),
            clockTime = f(clockTime, MetadataDefaults.CLOCK_TIME),
            clockQueued = f(clockQueued, MetadataDefaults.CLOCK_QUEUED),
            clockRecent = f(clockRecent, MetadataDefaults.CLOCK_RECENT),
            checkMark = f(checkMark, MetadataDefaults.CHECK),
            crossMark = f(crossMark, MetadataDefaults.CROSS),
            closeMark = f(closeMark, MetadataDefaults.CLOSE),
            checkboxOn = f(checkboxOn, MetadataDefaults.CHECKBOX_ON),
            checkboxOff = f(checkboxOff, MetadataDefaults.CHECKBOX_OFF),
            boxBlank = f(boxBlank, MetadataDefaults.BOX_BLANK),
            medalGold = f(medalGold, MetadataDefaults.MEDAL_GOLD),
            medalSilver = f(medalSilver, MetadataDefaults.MEDAL_SILVER),
            medalBronze = f(medalBronze, MetadataDefaults.MEDAL_BRONZE),
            arrowRight = f(arrowRight, MetadataDefaults.ARROW_RIGHT),
            arrowDown = f(arrowDown, MetadataDefaults.ARROW_DOWN),
            arrowSubmit = f(arrowSubmit, MetadataDefaults.ARROW_SUBMIT),
            agent = f(agent, MetadataDefaults.AGENT),
            aiFind = f(aiFind, MetadataDefaults.AI_FIND),
            web = f(web, MetadataDefaults.WEB),
            lookup = f(lookup, MetadataDefaults.LOOKUP),
            search = f(search, MetadataDefaults.SEARCH),
            folderOpen = f(folderOpen, MetadataDefaults.FOLDER_OPEN),
            label = f(label, MetadataDefaults.LABEL),
            bookmark = f(bookmark, MetadataDefaults.BOOKMARK),
            notepad = f(notepad, MetadataDefaults.NOTEPAD),
            packageBox = f(packageBox, MetadataDefaults.PACKAGE_BOX),
            world = f(world, MetadataDefaults.WORLD),
            chart = f(chart, MetadataDefaults.CHART),
            cyclone = f(cyclone, MetadataDefaults.CYCLONE),
            library = f(library, MetadataDefaults.LIBRARY),
            book = f(book, MetadataDefaults.BOOK),
            image = f(image, MetadataDefaults.IMAGE),
            mail = f(mail, MetadataDefaults.MAIL),
            speech = f(speech, MetadataDefaults.SPEECH),
            gem = f(gem, MetadataDefaults.GEM),
            tip = f(tip, MetadataDefaults.TIP),
            cost = f(cost, MetadataDefaults.COST),
            dollar = f(dollar, MetadataDefaults.DOLLAR),
            spend = f(spend, MetadataDefaults.SPEND),
            swarm = f(swarm, MetadataDefaults.SWARM),
            flock = f(flock, MetadataDefaults.FLOCK),
            fanInKnot = f(fanInKnot, MetadataDefaults.FAN_IN_KNOT),
            feather = f(feather, MetadataDefaults.FEATHER),
            tools = f(tools, MetadataDefaults.TOOLS),
            toolbox = f(toolbox, MetadataDefaults.TOOLBOX),
            puzzle = f(puzzle, MetadataDefaults.PUZZLE),
            palette = f(palette, MetadataDefaults.PALETTE),
            test = f(test, MetadataDefaults.TEST),
            worker = f(worker, MetadataDefaults.WORKER),
            sparkles = f(sparkles, MetadataDefaults.SPARKLES),
            ruler = f(ruler, MetadataDefaults.RULER),
            shuffle = f(shuffle, MetadataDefaults.SHUFFLE),
            hide = f(hide, MetadataDefaults.HIDE),
            device = f(device, MetadataDefaults.DEVICE),
            computer = f(computer, MetadataDefaults.COMPUTER),
            satellite = f(satellite, MetadataDefaults.SATELLITE),
            huggingface = f(huggingface, MetadataDefaults.HUGGINGFACE),
        )
    }
}
