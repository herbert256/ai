import Foundation

/// Format a large number into compact form (e.g., 1.5K, 2.3M, 4.1B).
func formatCompactNumber(_ value: Int64) -> String {
    switch value {
    case 1_000_000_000...:
        return String(format: "%.1fB", Double(value) / 1_000_000_000.0)
    case 1_000_000...:
        return String(format: "%.1fM", Double(value) / 1_000_000.0)
    case 1_000...:
        return String(format: "%.1fK", Double(value) / 1_000.0)
    default:
        return "\(value)"
    }
}

/// Format a per-token price as price per million tokens.
func formatTokenPricePerMillion(_ pricePerToken: Double) -> String {
    let perMillion = pricePerToken * 1_000_000
    switch perMillion {
    case 1...:
        return String(format: "$%.2f / 1M tokens", perMillion)
    case 0.01...:
        return String(format: "$%.4f / 1M tokens", perMillion)
    default:
        return String(format: "$%.6f / 1M tokens", perMillion)
    }
}

/// Format a dollar amount with the specified decimal places.
func formatUsd(_ value: Double, decimals: Int = 8) -> String {
    String(format: "$%.\(decimals)f", value)
}

/// Format a value in cents.
func formatCents(_ value: Double, decimals: Int = 4) -> String {
    String(format: "%.\(decimals)f¢", value * 100)
}

/// Format a decimal number.
func formatDecimal(_ value: Double, decimals: Int = 2) -> String {
    String(format: "%.\(decimals)f", value)
}

/// Format a date for display.
func formatDate(_ date: Date) -> String {
    let formatter = DateFormatter()
    formatter.dateStyle = .medium
    formatter.timeStyle = .short
    return formatter.string(from: date)
}

/// Format a date as relative time (e.g., "2 hours ago", "yesterday").
func formatRelativeDate(_ date: Date) -> String {
    let formatter = RelativeDateTimeFormatter()
    formatter.unitsStyle = .abbreviated
    return formatter.localizedString(for: date, relativeTo: Date())
}

/// Format a duration in milliseconds to a human-readable string.
func formatDuration(_ ms: Int) -> String {
    switch ms {
    case ..<1000:
        return "\(ms)ms"
    case ..<60000:
        return String(format: "%.1fs", Double(ms) / 1000.0)
    default:
        let minutes = ms / 60000
        let seconds = (ms % 60000) / 1000
        return "\(minutes)m \(seconds)s"
    }
}

/// Format a byte count to a human-readable string.
func formatBytes(_ bytes: Int64) -> String {
    let formatter = ByteCountFormatter()
    formatter.countStyle = .file
    return formatter.string(fromByteCount: bytes)
}
