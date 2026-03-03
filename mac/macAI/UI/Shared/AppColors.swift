import SwiftUI

/// Centralized color definitions matching the Android Material 3 dark theme.
enum AppColors {
    // Primary theme colors
    static let primary = Color(red: 0x4A/255, green: 0x9E/255, blue: 0xFF/255)       // #4A9EFF
    static let secondary = Color(red: 0x3A/255, green: 0x8E/255, blue: 0xEF/255)     // #3A8EEF
    static let background = Color(red: 0x0A/255, green: 0x0A/255, blue: 0x0A/255)    // #0A0A0A
    static let surface = Color(red: 0x0A/255, green: 0x0A/255, blue: 0x0A/255)       // #0A0A0A
    static let surfaceVariant = Color(red: 0x0F/255, green: 0x34/255, blue: 0x60/255) // #0F3460
    static let onBackground = Color(red: 0xEE/255, green: 0xEE/255, blue: 0xEE/255)  // #EEEEEE
    static let onSurface = Color(red: 0xEE/255, green: 0xEE/255, blue: 0xEE/255)     // #EEEEEE
    static let onSurfaceVariant = Color(red: 0x88/255, green: 0x88/255, blue: 0x88/255) // #888888
    static let error = Color(red: 0xFF/255, green: 0x47/255, blue: 0x57/255)         // #FF4757

    // Status colors
    static let success = Color(red: 0x2E/255, green: 0xCC/255, blue: 0x71/255)       // #2ECC71
    static let warning = Color(red: 0xF3/255, green: 0x9C/255, blue: 0x12/255)       // #F39C12
    static let info = Color(red: 0x34/255, green: 0x98/255, blue: 0xDB/255)          // #3498DB

    // Provider state colors
    static let stateOk = success
    static let stateError = error
    static let stateNotUsed = onSurfaceVariant
    static let stateInactive = Color(red: 0x55/255, green: 0x55/255, blue: 0x55/255)

    // Card/container colors
    static let cardBackground = Color(red: 0x12/255, green: 0x12/255, blue: 0x15/255) // Slightly lighter than bg
    static let cardBorder = Color(red: 0x22/255, green: 0x22/255, blue: 0x28/255)

    // Field colors (shared across settings)
    static let fieldBackground = Color(red: 0x15/255, green: 0x15/255, blue: 0x1A/255)
    static let fieldBorder = Color(red: 0x2A/255, green: 0x2A/255, blue: 0x35/255)

    // Status aliases (used across UI)
    static let statusOk = success
    static let statusError = error

    // Text colors
    static let textPrimary = onBackground
    static let textSecondary = onSurfaceVariant
    static let textTertiary = Color(red: 0x66/255, green: 0x66/255, blue: 0x66/255)
    static let textHint = Color(red: 0x55/255, green: 0x55/255, blue: 0x55/255)

    /// Color for a provider state string.
    static func colorForState(_ state: String) -> Color {
        switch state {
        case "ok": return stateOk
        case "error": return stateError
        case "inactive": return stateInactive
        default: return stateNotUsed
        }
    }
}
