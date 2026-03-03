import Foundation

// MARK: - General Settings

struct GeneralSettings: Codable, Equatable {
    var userName: String
    var developerMode: Bool
    var huggingFaceApiKey: String
    var openRouterApiKey: String
    var defaultEmail: String

    init(
        userName: String = "user",
        developerMode: Bool = true,
        huggingFaceApiKey: String = "",
        openRouterApiKey: String = "",
        defaultEmail: String = ""
    ) {
        self.userName = userName
        self.developerMode = developerMode
        self.huggingFaceApiKey = huggingFaceApiKey
        self.openRouterApiKey = openRouterApiKey
        self.defaultEmail = defaultEmail
    }
}

// MARK: - UI State

/// Main state for the AI app.
struct UiState {
    var isLoading = false
    var errorMessage: String?

    // General settings
    var generalSettings = GeneralSettings()

    // AI settings
    var aiSettings = Settings()

    // Model loading state
    var loadingModelsFor: Set<String> = []  // Set of AppService IDs

    // Reports
    var genericPromptTitle = ""
    var genericPromptText = ""
    var showGenericAgentSelection = false
    var showGenericReportsDialog = false
    var genericReportsProgress = 0
    var genericReportsTotal = 0
    var genericReportsSelectedAgents: Set<String> = []
    var genericReportsAgentResults: [String: AnalysisResponse] = [:]
    var currentReportId: String?
    var reportAdvancedParameters: AgentParameters?

    // Chat
    var chatParameters = ChatParameters()

    // Dual chat
    var dualChatConfig: DualChatConfig?

    // Usage stats
    var usageStats: [String: UsageStats] = [:]
}

// MARK: - Sidebar Section

/// Sidebar navigation sections for macOS NavigationSplitView.
enum SidebarSection: String, CaseIterable, Identifiable {
    case hub = "Hub"
    case newReport = "New Report"
    case reportHistory = "Report History"
    case promptHistory = "Prompt History"
    case chat = "Chat"
    case chatHistory = "Chat History"
    case dualChat = "Dual Chat"
    case modelSearch = "Model Search"
    case statistics = "AI Usage"
    case settings = "Settings"
    case setup = "AI Setup"
    case housekeeping = "Housekeeping"
    case traces = "API Traces"
    case developer = "Developer"
    case help = "Help"

    var id: String { rawValue }

    var icon: String {
        switch self {
        case .hub: return "house"
        case .newReport: return "doc.badge.plus"
        case .reportHistory: return "clock.arrow.circlepath"
        case .promptHistory: return "text.bubble"
        case .chat: return "bubble.left.and.bubble.right"
        case .chatHistory: return "archivebox"
        case .dualChat: return "rectangle.split.2x1"
        case .modelSearch: return "magnifyingglass"
        case .statistics: return "chart.bar"
        case .settings: return "gear"
        case .setup: return "wrench.and.screwdriver"
        case .housekeeping: return "hammer"
        case .traces: return "network"
        case .developer: return "terminal"
        case .help: return "questionmark.circle"
        }
    }

    var group: SidebarGroup {
        switch self {
        case .hub: return .main
        case .newReport, .reportHistory, .promptHistory: return .reports
        case .chat, .chatHistory, .dualChat: return .chat
        case .modelSearch, .statistics: return .tools
        case .settings, .setup, .housekeeping, .traces, .developer, .help: return .admin
        }
    }
}

enum SidebarGroup: String, CaseIterable {
    case main = "Main"
    case reports = "Reports"
    case chat = "Chat"
    case tools = "Tools"
    case admin = "Admin"
}
