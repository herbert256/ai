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
    const val TRANSLATOR_RANK = "🏅"
    // Report-level actions.
    const val COPY = "📋"
    const val PIN = "📌"
    const val TOGGLE_LABELS = "🔤"
    const val SHARE = "📤"
    const val IMPORT = "📥"
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
    const val ARROW_UP = "↑"
    const val ARROW_RIGHT = "→"
    const val ARROW_DOWN = "↓"
    const val ARROW_BACK = "◀"
    const val ARROW_FORWARD = "➡"
    const val ARROW_SUBMIT = "➤"
    const val CARET_EXPANDED = "▾"
    const val CARET_COLLAPSED = "▸"
    const val MINUS = "➖"
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
    const val DOCUMENT = "📄"
    const val PACKAGE_BOX = "📦"
    const val PLUG = "🔌"
    const val KEY = "🔑"
    const val SLEEP = "💤"
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
    const val CAMERA = "📸"
    const val GIFT = "🎁"
    const val ROCKET = "🚀"
    const val HOME = "🏠"
    const val SAVE = "💾"
    const val CLOUD = "☁️"
    const val BUILDING_BLOCKS = "🧱"
    const val GROUPINGS = "🪺"
    const val GITHUB = "🐙"
    // Cost
    const val COST = "💰"
    const val DOLLAR = "💲"
    const val SPEND = "💸"
    const val CASH = "💵"
    // Workers & tools
    const val SWARM = "🐝"
    /** Per-batch worker breakdown screen (Tournament / Fan Meta /
     *  Translation), reached via the 🐜 bottom-bar icon. */
    const val ANT = "🐜"
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
    const val RULER_STRAIGHT = "📏"
    const val SHUFFLE = "🔀"
    const val REPEAT = "🔁"
    const val HIDE = "🙈"
    const val SHIELD = "🛡"
    const val MICROSCOPE = "🔬"
    const val CONTROLS = "🎚️"
    const val SLIDERS = "🎛️"
    const val MAGIC = "🪄"
    // Devices & misc
    const val DEVICE = "📱"
    const val COMPUTER = "💻"
    const val SATELLITE = "🛰"
    const val HUGGINGFACE = "🤗"
    const val GREEN_CIRCLE = "🟢"
    const val WHITE_CIRCLE = "⚪"
    const val RED_CIRCLE = "🔴"
    const val ORANGE_CIRCLE = "🟠"
    const val BLUE_CIRCLE = "🔵"
    const val SUN = "☀️"
    const val CALENDAR = "📅"
    const val CALENDAR_SPIRAL = "🗓️"
    const val RUNNER = "🏃"
    const val ROADBLOCK = "🚧"
    const val EXPLOSION = "💥"
    const val SNOWFLAKE = "❄️"
    const val NO_ENTRY = "⛔"
    const val FOG = "🌫️"
    const val BENTO = "🍱"
    const val NUMBER_INPUT = "🔢"
    const val SYMBOLS = "🔣"
    const val HANDSHAKE = "🤝"
    const val BLUE_DIAMOND = "🔷"
    const val GROUP = "👥"
    const val WARNING_PLAIN = "⚠"
    const val BOLT = "⚡"
    const val HEALTH = "🩺"
    const val SLOW = "🐌"
    const val COFFIN = "⚰️"
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
    val importReport: String = MetadataDefaults.IMPORT,
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
    val translatorRank: String = MetadataDefaults.TRANSLATOR_RANK,
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
    val arrowUp: String = MetadataDefaults.ARROW_UP,
    val arrowRight: String = MetadataDefaults.ARROW_RIGHT,
    val arrowDown: String = MetadataDefaults.ARROW_DOWN,
    val arrowBack: String = MetadataDefaults.ARROW_BACK,
    val arrowForward: String = MetadataDefaults.ARROW_FORWARD,
    val arrowSubmit: String = MetadataDefaults.ARROW_SUBMIT,
    val caretExpanded: String = MetadataDefaults.CARET_EXPANDED,
    val caretCollapsed: String = MetadataDefaults.CARET_COLLAPSED,
    val minus: String = MetadataDefaults.MINUS,
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
    val document: String = MetadataDefaults.DOCUMENT,
    val packageBox: String = MetadataDefaults.PACKAGE_BOX,
    val plug: String = MetadataDefaults.PLUG,
    val key: String = MetadataDefaults.KEY,
    val sleep: String = MetadataDefaults.SLEEP,
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
    val camera: String = MetadataDefaults.CAMERA,
    val gift: String = MetadataDefaults.GIFT,
    val rocket: String = MetadataDefaults.ROCKET,
    val home: String = MetadataDefaults.HOME,
    val save: String = MetadataDefaults.SAVE,
    val cloud: String = MetadataDefaults.CLOUD,
    val buildingBlocks: String = MetadataDefaults.BUILDING_BLOCKS,
    val groupings: String = MetadataDefaults.GROUPINGS,
    val github: String = MetadataDefaults.GITHUB,
    // Cost
    val cost: String = MetadataDefaults.COST,
    val dollar: String = MetadataDefaults.DOLLAR,
    val spend: String = MetadataDefaults.SPEND,
    val cash: String = MetadataDefaults.CASH,
    // Workers & tools
    val swarm: String = MetadataDefaults.SWARM,
    val ant: String = MetadataDefaults.ANT,
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
    val rulerStraight: String = MetadataDefaults.RULER_STRAIGHT,
    val shuffle: String = MetadataDefaults.SHUFFLE,
    val repeat: String = MetadataDefaults.REPEAT,
    val hide: String = MetadataDefaults.HIDE,
    val shield: String = MetadataDefaults.SHIELD,
    val microscope: String = MetadataDefaults.MICROSCOPE,
    val controls: String = MetadataDefaults.CONTROLS,
    val sliders: String = MetadataDefaults.SLIDERS,
    val magic: String = MetadataDefaults.MAGIC,
    // Devices & misc
    val device: String = MetadataDefaults.DEVICE,
    val computer: String = MetadataDefaults.COMPUTER,
    val satellite: String = MetadataDefaults.SATELLITE,
    val huggingface: String = MetadataDefaults.HUGGINGFACE,
    val greenCircle: String = MetadataDefaults.GREEN_CIRCLE,
    val whiteCircle: String = MetadataDefaults.WHITE_CIRCLE,
    val redCircle: String = MetadataDefaults.RED_CIRCLE,
    val orangeCircle: String = MetadataDefaults.ORANGE_CIRCLE,
    val blueCircle: String = MetadataDefaults.BLUE_CIRCLE,
    val sun: String = MetadataDefaults.SUN,
    val calendar: String = MetadataDefaults.CALENDAR,
    val calendarSpiral: String = MetadataDefaults.CALENDAR_SPIRAL,
    val runner: String = MetadataDefaults.RUNNER,
    val roadblock: String = MetadataDefaults.ROADBLOCK,
    val explosion: String = MetadataDefaults.EXPLOSION,
    val snowflake: String = MetadataDefaults.SNOWFLAKE,
    val noEntry: String = MetadataDefaults.NO_ENTRY,
    val fog: String = MetadataDefaults.FOG,
    val bento: String = MetadataDefaults.BENTO,
    val numberInput: String = MetadataDefaults.NUMBER_INPUT,
    val symbols: String = MetadataDefaults.SYMBOLS,
    val handshake: String = MetadataDefaults.HANDSHAKE,
    val blueDiamond: String = MetadataDefaults.BLUE_DIAMOND,
    val group: String = MetadataDefaults.GROUP,
    val warningPlain: String = MetadataDefaults.WARNING_PLAIN,
    val bolt: String = MetadataDefaults.BOLT,
    val health: String = MetadataDefaults.HEALTH,
    val slow: String = MetadataDefaults.SLOW,
    val coffin: String = MetadataDefaults.COFFIN,
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
        SecondaryKind.TRANSRANK -> translatorRank
    }

    fun forFactoryGlyph(factoryGlyph: String): String =
        factoryGlyphMap()[factoryGlyph] ?: factoryGlyph

    fun iconizedText(text: String): String {
        var out = text
        factoryGlyphMap().entries
            .sortedByDescending { it.key.length }
            .forEach { (factory, live) ->
                if (factory != live) out = out.replace(factory, live)
            }
        return out
    }

    private fun factoryGlyphMap(): Map<String, String> {
        val map = linkedMapOf<String, String>()
        for (field in STRING_FIELDS) {
            val factory = field.get(DEFAULT_INSTANCE) as? String ?: continue
            val live = field.get(this) as? String ?: continue
            if (factory.isNotBlank() && live.isNotBlank()) map.putIfAbsent(factory, live)
        }
        return map
    }

    companion object {
        private val STRING_FIELDS: List<java.lang.reflect.Field> by lazy {
            MetadataIcons::class.java.declaredFields
                .filter { it.type == String::class.java }
                .onEach { it.isAccessible = true }
        }
        private val DEFAULT_INSTANCE by lazy { MetadataIcons() }
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
            importReport = f(importReport, MetadataDefaults.IMPORT),
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
            translatorRank = f(translatorRank, MetadataDefaults.TRANSLATOR_RANK),
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
            arrowUp = f(arrowUp, MetadataDefaults.ARROW_UP),
            arrowRight = f(arrowRight, MetadataDefaults.ARROW_RIGHT),
            arrowDown = f(arrowDown, MetadataDefaults.ARROW_DOWN),
            arrowBack = f(arrowBack, MetadataDefaults.ARROW_BACK),
            arrowForward = f(arrowForward, MetadataDefaults.ARROW_FORWARD),
            arrowSubmit = f(arrowSubmit, MetadataDefaults.ARROW_SUBMIT),
            caretExpanded = f(caretExpanded, MetadataDefaults.CARET_EXPANDED),
            caretCollapsed = f(caretCollapsed, MetadataDefaults.CARET_COLLAPSED),
            minus = f(minus, MetadataDefaults.MINUS),
            agent = f(agent, MetadataDefaults.AGENT),
            aiFind = f(aiFind, MetadataDefaults.AI_FIND),
            web = f(web, MetadataDefaults.WEB),
            lookup = f(lookup, MetadataDefaults.LOOKUP),
            search = f(search, MetadataDefaults.SEARCH),
            folderOpen = f(folderOpen, MetadataDefaults.FOLDER_OPEN),
            label = f(label, MetadataDefaults.LABEL),
            bookmark = f(bookmark, MetadataDefaults.BOOKMARK),
            notepad = f(notepad, MetadataDefaults.NOTEPAD),
            document = f(document, MetadataDefaults.DOCUMENT),
            packageBox = f(packageBox, MetadataDefaults.PACKAGE_BOX),
            plug = f(plug, MetadataDefaults.PLUG),
            key = f(key, MetadataDefaults.KEY),
            sleep = f(sleep, MetadataDefaults.SLEEP),
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
            camera = f(camera, MetadataDefaults.CAMERA),
            gift = f(gift, MetadataDefaults.GIFT),
            rocket = f(rocket, MetadataDefaults.ROCKET),
            home = f(home, MetadataDefaults.HOME),
            save = f(save, MetadataDefaults.SAVE),
            cloud = f(cloud, MetadataDefaults.CLOUD),
            buildingBlocks = f(buildingBlocks, MetadataDefaults.BUILDING_BLOCKS),
            groupings = f(groupings, MetadataDefaults.GROUPINGS),
            github = f(github, MetadataDefaults.GITHUB),
            cost = f(cost, MetadataDefaults.COST),
            dollar = f(dollar, MetadataDefaults.DOLLAR),
            spend = f(spend, MetadataDefaults.SPEND),
            cash = f(cash, MetadataDefaults.CASH),
            swarm = f(swarm, MetadataDefaults.SWARM),
            ant = f(ant, MetadataDefaults.ANT),
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
            rulerStraight = f(rulerStraight, MetadataDefaults.RULER_STRAIGHT),
            shuffle = f(shuffle, MetadataDefaults.SHUFFLE),
            repeat = f(repeat, MetadataDefaults.REPEAT),
            hide = f(hide, MetadataDefaults.HIDE),
            shield = f(shield, MetadataDefaults.SHIELD),
            microscope = f(microscope, MetadataDefaults.MICROSCOPE),
            controls = f(controls, MetadataDefaults.CONTROLS),
            sliders = f(sliders, MetadataDefaults.SLIDERS),
            magic = f(magic, MetadataDefaults.MAGIC),
            device = f(device, MetadataDefaults.DEVICE),
            computer = f(computer, MetadataDefaults.COMPUTER),
            satellite = f(satellite, MetadataDefaults.SATELLITE),
            huggingface = f(huggingface, MetadataDefaults.HUGGINGFACE),
            greenCircle = f(greenCircle, MetadataDefaults.GREEN_CIRCLE),
            whiteCircle = f(whiteCircle, MetadataDefaults.WHITE_CIRCLE),
            redCircle = f(redCircle, MetadataDefaults.RED_CIRCLE),
            orangeCircle = f(orangeCircle, MetadataDefaults.ORANGE_CIRCLE),
            blueCircle = f(blueCircle, MetadataDefaults.BLUE_CIRCLE),
            sun = f(sun, MetadataDefaults.SUN),
            calendar = f(calendar, MetadataDefaults.CALENDAR),
            calendarSpiral = f(calendarSpiral, MetadataDefaults.CALENDAR_SPIRAL),
            runner = f(runner, MetadataDefaults.RUNNER),
            roadblock = f(roadblock, MetadataDefaults.ROADBLOCK),
            explosion = f(explosion, MetadataDefaults.EXPLOSION),
            snowflake = f(snowflake, MetadataDefaults.SNOWFLAKE),
            noEntry = f(noEntry, MetadataDefaults.NO_ENTRY),
            fog = f(fog, MetadataDefaults.FOG),
            bento = f(bento, MetadataDefaults.BENTO),
            numberInput = f(numberInput, MetadataDefaults.NUMBER_INPUT),
            symbols = f(symbols, MetadataDefaults.SYMBOLS),
            handshake = f(handshake, MetadataDefaults.HANDSHAKE),
            blueDiamond = f(blueDiamond, MetadataDefaults.BLUE_DIAMOND),
            group = f(group, MetadataDefaults.GROUP),
            warningPlain = f(warningPlain, MetadataDefaults.WARNING_PLAIN),
            bolt = f(bolt, MetadataDefaults.BOLT),
            health = f(health, MetadataDefaults.HEALTH),
            slow = f(slow, MetadataDefaults.SLOW),
            coffin = f(coffin, MetadataDefaults.COFFIN),
        )
    }
}
