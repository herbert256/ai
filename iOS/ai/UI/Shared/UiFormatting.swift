import Foundation

// MARK: - UI Formatting Utilities

struct UiFormatting {

    // MARK: - Numbers

    static func formatTokens(_ count: Int) -> String {
        if count >= 1_000_000 { return String(format: "%.1fM", Double(count) / 1_000_000) }
        if count >= 1_000 { return String(format: "%.1fK", Double(count) / 1_000) }
        return "\(count)"
    }

    static func formatTokens(_ count: Int64) -> String {
        if count >= 1_000_000 { return String(format: "%.1fM", Double(count) / 1_000_000) }
        if count >= 1_000 { return String(format: "%.1fK", Double(count) / 1_000) }
        return "\(count)"
    }

    static func formatCost(_ cost: Double) -> String {
        if cost == 0 { return "$0.00" }
        if cost < 0.001 { return String(format: "$%.6f", cost) }
        if cost < 0.01 { return String(format: "$%.4f", cost) }
        if cost < 1.0 { return String(format: "$%.3f", cost) }
        return String(format: "$%.2f", cost)
    }

    static func formatCostPerMillion(_ pricePerToken: Double) -> String {
        let perMillion = pricePerToken * 1_000_000
        if perMillion < 0.01 { return String(format: "$%.4f/M", perMillion) }
        return String(format: "$%.2f/M", perMillion)
    }

    // MARK: - Time

    static func formatTimestamp(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(millis) / 1000)
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    static func formatDuration(_ millis: Int64) -> String {
        let seconds = Double(millis) / 1000
        if seconds < 1 { return "\(millis)ms" }
        if seconds < 60 { return String(format: "%.1fs", seconds) }
        let minutes = Int(seconds) / 60
        let secs = Int(seconds) % 60
        return "\(minutes)m \(secs)s"
    }

    static func formatRelativeTime(_ millis: Int64) -> String {
        let seconds = (Double(Date().timeIntervalSince1970 * 1000) - Double(millis)) / 1000
        if seconds < 60 { return "just now" }
        if seconds < 3600 { return "\(Int(seconds / 60))m ago" }
        if seconds < 86400 { return "\(Int(seconds / 3600))h ago" }
        let days = Int(seconds / 86400)
        if days == 1 { return "yesterday" }
        if days < 30 { return "\(days)d ago" }
        return formatTimestamp(millis)
    }

    // MARK: - Text

    static func truncate(_ text: String, maxLength: Int = 50) -> String {
        if text.count <= maxLength { return text }
        return String(text.prefix(maxLength)) + "..."
    }

    static func formatModelName(_ model: String) -> String {
        // Strip common prefixes for display
        if model.contains("/") {
            return String(model.split(separator: "/").last ?? Substring(model))
        }
        return model
    }

    // MARK: - Provider State

    static func providerStateLabel(_ state: String) -> String {
        switch state {
        case "ok": return "Active"
        case "error": return "Error"
        case "not-used": return "Not configured"
        case "inactive": return "Inactive"
        default: return state
        }
    }

    static func providerStateEmoji(_ state: String) -> String {
        switch state {
        case "ok": return ""
        case "error": return ""
        case "not-used": return ""
        case "inactive": return ""
        default: return ""
        }
    }
}
