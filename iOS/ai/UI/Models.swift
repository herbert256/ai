import Foundation

// MARK: - General Settings

struct GeneralSettings: Codable, Equatable, Sendable {
    var userName: String = "user"
    var developerMode: Bool = true
    var huggingFaceApiKey: String = ""
    var openRouterApiKey: String = ""
    var fullScreenMode: Bool = false
    var defaultEmail: String = ""
    var popupModelSelection: Bool = true
}

// MARK: - UI State

@MainActor
struct UiState {
    var isLoading: Bool = false
    var errorMessage: String?

    // Settings
    var generalSettings = GeneralSettings()
    var aiSettings = Settings()
    var loadingModelsFor: Set<String> = []  // Provider IDs

    // Generic AI Reports
    var showGenericAgentSelection = false
    var showGenericReportsDialog = false
    var genericPromptTitle = ""
    var genericPromptText = ""
    var genericReportsProgress = 0
    var genericReportsTotal = 0
    var genericReportsSelectedAgents: Set<String> = []
    var genericReportsAgentResults: [String: AnalysisResponse] = [:]
    var currentReportId: String?

    // Report Advanced Parameters
    var reportAdvancedParameters: AgentParameters?

    // External Intent Parameters (deep linking)
    var externalSystemPrompt: String?
    var externalCloseHtml: String?
    var externalReportType: String?
    var externalEmail: String?
    var externalNextAction: String?
    var externalReturn = false
    var externalEdit = false
    var externalSelect = false
    var externalOpenHtml: String?
    var externalAgentNames: [String] = []
    var externalFlockNames: [String] = []
    var externalSwarmNames: [String] = []
    var externalModelSpecs: [String] = []

    // Chat
    var chatParameters = ChatParameters()
    var dualChatConfig: DualChatConfig?
}
