import Foundation
import os

/// Settings persistence using UserDefaults.
/// Unlike Android where flock/swarm keys are historically swapped,
/// macOS uses correct key names from the start.
enum SettingsPreferences {
    private static let logger = Logger(subsystem: "com.ai.macAI", category: "SettingsPreferences")

    private static let settingsKey = "ai_settings_json"
    private static let generalSettingsKey = "general_settings_json"
    private static let usageStatsKey = "usage_stats_json"
    private static let promptHistoryKey = "prompt_history_json"

    // MARK: - Settings

    static func loadSettings() -> Settings {
        guard let json = UserDefaults.standard.string(forKey: settingsKey),
              let data = json.data(using: .utf8) else {
            return Settings()
        }
        do {
            return try JSONDecoder().decode(Settings.self, from: data)
        } catch {
            logger.error("Failed to load settings: \(error.localizedDescription)")
            return Settings()
        }
    }

    static func saveSettings(_ settings: Settings) {
        do {
            let encoder = JSONEncoder()
            encoder.outputFormatting = .prettyPrinted
            let data = try encoder.encode(settings)
            UserDefaults.standard.set(String(data: data, encoding: .utf8), forKey: settingsKey)
        } catch {
            logger.error("Failed to save settings: \(error.localizedDescription)")
        }
    }

    // MARK: - General Settings

    static func loadGeneralSettings() -> GeneralSettings {
        guard let json = UserDefaults.standard.string(forKey: generalSettingsKey),
              let data = json.data(using: .utf8) else {
            return GeneralSettings()
        }
        return (try? JSONDecoder().decode(GeneralSettings.self, from: data)) ?? GeneralSettings()
    }

    static func saveGeneralSettings(_ settings: GeneralSettings) {
        if let data = try? JSONEncoder().encode(settings) {
            UserDefaults.standard.set(String(data: data, encoding: .utf8), forKey: generalSettingsKey)
        }
    }

    // MARK: - Usage Stats

    static func loadUsageStats() -> [String: UsageStats] {
        guard let json = UserDefaults.standard.string(forKey: usageStatsKey),
              let data = json.data(using: .utf8) else {
            return [:]
        }
        return (try? JSONDecoder().decode([String: UsageStats].self, from: data)) ?? [:]
    }

    static func saveUsageStats(_ stats: [String: UsageStats]) {
        if let data = try? JSONEncoder().encode(stats) {
            UserDefaults.standard.set(String(data: data, encoding: .utf8), forKey: usageStatsKey)
        }
    }

    // MARK: - Prompt History

    static func loadPromptHistory() -> [PromptHistoryEntry] {
        guard let json = UserDefaults.standard.string(forKey: promptHistoryKey),
              let data = json.data(using: .utf8) else {
            return []
        }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        return (try? decoder.decode([PromptHistoryEntry].self, from: data)) ?? []
    }

    static func savePromptHistory(_ history: [PromptHistoryEntry]) {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        if let data = try? encoder.encode(history) {
            UserDefaults.standard.set(String(data: data, encoding: .utf8), forKey: promptHistoryKey)
        }
    }

    static func addPromptHistoryEntry(title: String, prompt: String) {
        var history = loadPromptHistory()
        history.insert(PromptHistoryEntry(timestamp: Date(), title: title, prompt: prompt), at: 0)
        // Keep last 100 entries
        if history.count > 100 { history = Array(history.prefix(100)) }
        savePromptHistory(history)
    }
}
