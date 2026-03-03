import SwiftUI

// MARK: - App Colors (matching Android Material3 dark theme)

struct AppColors {
    // Primary
    static let primary = Color(red: 0.82, green: 0.77, blue: 1.0)       // ~D0C3FF
    static let onPrimary = Color(red: 0.2, green: 0.11, blue: 0.47)
    static let primaryContainer = Color(red: 0.33, green: 0.24, blue: 0.61)
    static let onPrimaryContainer = Color(red: 0.91, green: 0.87, blue: 1.0)

    // Surface / Background
    static let surface = Color(red: 0.07, green: 0.07, blue: 0.07)       // ~121212
    static let surfaceVariant = Color(red: 0.18, green: 0.17, blue: 0.21)
    static let onSurface = Color(red: 0.91, green: 0.87, blue: 0.93)
    static let onSurfaceVariant = Color(red: 0.79, green: 0.75, blue: 0.85)
    static let background = Color(red: 0.07, green: 0.07, blue: 0.07)

    // Secondary
    static let secondary = Color(red: 0.79, green: 0.78, blue: 0.90)
    static let onSecondary = Color(red: 0.19, green: 0.19, blue: 0.34)
    static let secondaryContainer = Color(red: 0.30, green: 0.30, blue: 0.46)

    // Tertiary
    static let tertiary = Color(red: 1.0, green: 0.71, blue: 0.76)
    static let onTertiary = Color(red: 0.39, green: 0.11, blue: 0.18)

    // Error
    static let error = Color(red: 1.0, green: 0.71, blue: 0.67)
    static let onError = Color(red: 0.41, green: 0.0, blue: 0.04)

    // Outline
    static let outline = Color(red: 0.58, green: 0.55, blue: 0.67)
    static let outlineVariant = Color(red: 0.29, green: 0.27, blue: 0.35)

    // Functional
    static let success = Color(red: 0.56, green: 0.93, blue: 0.56)
    static let warning = Color(red: 1.0, green: 0.84, blue: 0.40)
    static let info = Color(red: 0.53, green: 0.81, blue: 0.92)

    // Card / elevated surfaces
    static let cardBackground = Color(red: 0.11, green: 0.11, blue: 0.13)
    static let cardBorder = Color(red: 0.22, green: 0.21, blue: 0.26)

    // Provider state colors
    static let stateOk = Color.green
    static let stateError = Color.red
    static let stateNotUsed = Color.gray
    static let stateInactive = Color.yellow

    // Misc
    static let dimText = Color(white: 0.5)
    static let accentBlue = Color(red: 0.40, green: 0.60, blue: 1.0)
    static let accentYellow = Color(red: 1.0, green: 0.84, blue: 0.40)
}
