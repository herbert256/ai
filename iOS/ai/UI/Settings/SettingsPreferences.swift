import Foundation

// MARK: - Settings Preferences

class SettingsPreferences: @unchecked Sendable {
    static let shared = SettingsPreferences()

    private let prefs = UserDefaults.standard

    // Preference keys
    static let prefsName = "eval_prefs"
    static let keyUserName = "user_name"
    static let keyDeveloperMode = "developer_mode"
    static let keyTrackApiCalls = "track_api_calls"
    static let keyHuggingFaceApiKey = "huggingface_api_key"
    static let keyOpenRouterApiKey = "openrouter_api_key"
    static let keyFullScreenMode = "full_screen_mode"
    static let keyDefaultEmail = "default_email"
    static let keyPopupModelSelection = "popup_model_selection"

    static let keyAiAgents = "ai_agents"
    // IMPORTANT: Historically swapped keys!
    static let keyAiFlocks = "ai_flocks_v2"      // Actually stores SWARMS
    static let keyAiSwarms = "ai_swarms_v2"       // Actually stores FLOCKS
    static let keyAiParameters = "ai_parameters"
    static let keyAiSystemPrompts = "ai_system_prompts"
    static let keyAiPrompts = "ai_prompts"
    static let keyAiEndpoints = "ai_endpoints"
    static let keyProviderStates = "provider_states"

    static let keyLastReportTitle = "last_ai_report_title"
    static let keyLastReportPrompt = "last_ai_report_prompt"
    static let keySelectedFlockIds = "selected_flock_ids_v2"
    static let keySelectedSwarmIds = "selected_swarm_ids_v2"
    static let maxPromptHistory = 100

    static let keyModelListTimestampPrefix = "model_list_timestamp_"
    static let modelListsCacheDurationMs: Int64 = 24 * 60 * 60 * 1000

    static let fileUsageStats = "usage-stats.json"
    static let filePromptHistory = "prompt-history.json"

    private var appSupportDir: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }

    // MARK: - General Settings

    func loadGeneralSettings() -> GeneralSettings {
        GeneralSettings(
            userName: prefs.string(forKey: Self.keyUserName) ?? "user",
            developerMode: prefs.object(forKey: Self.keyDeveloperMode) as? Bool ?? true,
            huggingFaceApiKey: prefs.string(forKey: Self.keyHuggingFaceApiKey) ?? "",
            openRouterApiKey: prefs.string(forKey: Self.keyOpenRouterApiKey) ?? "",
            fullScreenMode: prefs.bool(forKey: Self.keyFullScreenMode),
            defaultEmail: prefs.string(forKey: Self.keyDefaultEmail) ?? "",
            popupModelSelection: prefs.object(forKey: Self.keyPopupModelSelection) as? Bool ?? true
        )
    }

    func saveGeneralSettings(_ settings: GeneralSettings) {
        prefs.set(settings.userName, forKey: Self.keyUserName)
        prefs.set(settings.developerMode, forKey: Self.keyDeveloperMode)
        prefs.set(settings.huggingFaceApiKey, forKey: Self.keyHuggingFaceApiKey)
        prefs.set(settings.openRouterApiKey, forKey: Self.keyOpenRouterApiKey)
        prefs.set(settings.fullScreenMode, forKey: Self.keyFullScreenMode)
        prefs.set(settings.defaultEmail, forKey: Self.keyDefaultEmail)
        prefs.set(settings.popupModelSelection, forKey: Self.keyPopupModelSelection)
    }

    // MARK: - AI Settings

    func loadSettings() async -> Settings {
        var settings = Settings()

        // Load provider configs from registry
        let providers = await ProviderRegistry.shared.getAll()
        for service in providers {
            let key = service.prefsKey
            guard !key.isEmpty else { continue }

            var config = ProviderConfig()
            config.apiKey = prefs.string(forKey: "\(key)_api_key") ?? ""
            config.model = prefs.string(forKey: "\(key)_model") ?? service.defaultModel
            config.modelSource = ModelSource(rawValue: prefs.string(forKey: "\(key)_model_source") ?? "API") ?? .api
            config.models = loadJsonArray(forKey: "\(key)_manual_models") ?? service.hardcodedModels ?? []
            config.modelListUrl = prefs.string(forKey: "\(key)_model_list_url") ?? ""
            config.parametersIds = loadJsonArray(forKey: "\(key)_parameters_ids") ?? []
            settings.providers[service.id] = config
        }

        // Load complex settings
        settings.agents = loadCodable(forKey: Self.keyAiAgents) ?? []
        // SWAPPED KEYS: flocks stored under swarms key and vice versa
        settings.flocks = loadCodable(forKey: Self.keyAiSwarms) ?? []
        settings.swarms = loadCodable(forKey: Self.keyAiFlocks) ?? []
        settings.parameters = loadCodable(forKey: Self.keyAiParameters) ?? []
        settings.systemPrompts = loadCodable(forKey: Self.keyAiSystemPrompts) ?? []
        settings.prompts = loadCodable(forKey: Self.keyAiPrompts) ?? []
        settings.providerStates = loadCodable(forKey: Self.keyProviderStates) ?? [:]

        // Load endpoints
        if let endpointsMap: [String: [Endpoint]] = loadCodable(forKey: Self.keyAiEndpoints) {
            settings.endpoints = endpointsMap
        }

        return settings
    }

    func saveSettings(_ settings: Settings) async {
        let providers = await ProviderRegistry.shared.getAll()
        for service in providers {
            let key = service.prefsKey
            guard !key.isEmpty else { continue }

            let config = settings.getProvider(service.id)
            prefs.set(config.apiKey, forKey: "\(key)_api_key")
            prefs.set(config.model, forKey: "\(key)_model")
            prefs.set(config.modelSource.rawValue, forKey: "\(key)_model_source")
            saveJsonArray(config.models, forKey: "\(key)_manual_models")
            prefs.set(config.modelListUrl, forKey: "\(key)_model_list_url")
            saveJsonArray(config.parametersIds, forKey: "\(key)_parameters_ids")
        }

        saveCodable(settings.agents, forKey: Self.keyAiAgents)
        // SWAPPED KEYS
        saveCodable(settings.flocks, forKey: Self.keyAiSwarms)
        saveCodable(settings.swarms, forKey: Self.keyAiFlocks)
        saveCodable(settings.parameters, forKey: Self.keyAiParameters)
        saveCodable(settings.systemPrompts, forKey: Self.keyAiSystemPrompts)
        saveCodable(settings.prompts, forKey: Self.keyAiPrompts)
        saveCodable(settings.providerStates, forKey: Self.keyProviderStates)
        saveCodable(settings.endpoints, forKey: Self.keyAiEndpoints)
    }

    // MARK: - Prompt History

    func loadPromptHistory() -> [PromptHistoryEntry] {
        let file = appSupportDir.appendingPathComponent(Self.filePromptHistory)
        guard let data = try? Data(contentsOf: file) else { return [] }
        return (try? JSONDecoder().decode([PromptHistoryEntry].self, from: data)) ?? []
    }

    func savePromptToHistory(title: String, prompt: String) {
        var history = loadPromptHistory()
        let entry = PromptHistoryEntry(timestamp: Int64(Date().timeIntervalSince1970 * 1000), title: title, prompt: prompt)
        history.insert(entry, at: 0)
        if history.count > Self.maxPromptHistory { history = Array(history.prefix(Self.maxPromptHistory)) }
        savePromptHistoryList(history)
    }

    func savePromptHistoryList(_ entries: [PromptHistoryEntry]) {
        let file = appSupportDir.appendingPathComponent(Self.filePromptHistory)
        try? FileManager.default.createDirectory(at: appSupportDir, withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(entries) {
            try? data.write(to: file, options: .atomic)
        }
    }

    func clearPromptHistory() {
        let file = appSupportDir.appendingPathComponent(Self.filePromptHistory)
        try? FileManager.default.removeItem(at: file)
    }

    // MARK: - Last Report

    var lastReportTitle: String {
        get { prefs.string(forKey: Self.keyLastReportTitle) ?? "" }
        set { prefs.set(newValue, forKey: Self.keyLastReportTitle) }
    }

    var lastReportPrompt: String {
        get { prefs.string(forKey: Self.keyLastReportPrompt) ?? "" }
        set { prefs.set(newValue, forKey: Self.keyLastReportPrompt) }
    }

    func clearLastReportPrompt() {
        prefs.removeObject(forKey: Self.keyLastReportTitle)
        prefs.removeObject(forKey: Self.keyLastReportPrompt)
    }

    // MARK: - Selected IDs

    func loadSelectedFlockIds() -> Set<String> {
        Set(loadJsonArray(forKey: Self.keySelectedFlockIds) ?? [])
    }

    func saveSelectedFlockIds(_ ids: Set<String>) {
        saveJsonArray(Array(ids), forKey: Self.keySelectedFlockIds)
    }

    func loadSelectedSwarmIds() -> Set<String> {
        Set(loadJsonArray(forKey: Self.keySelectedSwarmIds) ?? [])
    }

    func saveSelectedSwarmIds(_ ids: Set<String>) {
        saveJsonArray(Array(ids), forKey: Self.keySelectedSwarmIds)
    }

    func clearSelectedReportIds() {
        prefs.removeObject(forKey: Self.keySelectedFlockIds)
        prefs.removeObject(forKey: Self.keySelectedSwarmIds)
    }

    // MARK: - Usage Statistics

    func loadUsageStats() -> [String: UsageStats] {
        let file = appSupportDir.appendingPathComponent(Self.fileUsageStats)
        guard let data = try? Data(contentsOf: file) else { return [:] }
        return (try? JSONDecoder().decode([String: UsageStats].self, from: data)) ?? [:]
    }

    func saveUsageStats(_ stats: [String: UsageStats]) {
        let file = appSupportDir.appendingPathComponent(Self.fileUsageStats)
        try? FileManager.default.createDirectory(at: appSupportDir, withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(stats) {
            try? data.write(to: file, options: .atomic)
        }
    }

    func updateUsageStats(providerId: String, model: String, inputTokens: Int, outputTokens: Int) async {
        var stats = loadUsageStats()
        let key = "\(providerId)::\(model)"
        var entry = stats[key] ?? UsageStats(providerId: providerId, model: model)
        entry.callCount += 1
        entry.inputTokens += Int64(inputTokens)
        entry.outputTokens += Int64(outputTokens)
        stats[key] = entry
        saveUsageStats(stats)
    }

    func clearUsageStats() {
        let file = appSupportDir.appendingPathComponent(Self.fileUsageStats)
        try? FileManager.default.removeItem(at: file)
    }

    // MARK: - Model List Cache

    func isModelListCacheValid(serviceId: String) -> Bool {
        let timestamp = prefs.object(forKey: "\(Self.keyModelListTimestampPrefix)\(serviceId)") as? Int64 ?? 0
        if timestamp == 0 { return false }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return (now - timestamp) < Self.modelListsCacheDurationMs
    }

    func updateModelListTimestamp(serviceId: String) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        prefs.set(now, forKey: "\(Self.keyModelListTimestampPrefix)\(serviceId)")
    }

    func clearModelListsCache() {
        let providers = prefs.dictionaryRepresentation().keys.filter { $0.hasPrefix(Self.keyModelListTimestampPrefix) }
        for key in providers { prefs.removeObject(forKey: key) }
    }

    // MARK: - JSON Helpers

    private func loadCodable<T: Decodable>(forKey key: String) -> T? {
        guard let json = prefs.string(forKey: key),
              let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private func saveCodable<T: Encodable>(_ value: T, forKey key: String) {
        guard let data = try? JSONEncoder().encode(value),
              let json = String(data: data, encoding: .utf8) else { return }
        prefs.set(json, forKey: key)
    }

    private func loadJsonArray(forKey key: String) -> [String]? {
        guard let json = prefs.string(forKey: key),
              let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode([String].self, from: data)
    }

    private func saveJsonArray(_ array: [String], forKey key: String) {
        guard let data = try? JSONEncoder().encode(array),
              let json = String(data: data, encoding: .utf8) else { return }
        prefs.set(json, forKey: key)
    }
}
