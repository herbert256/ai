pragma Singleton
import QtQuick

QtObject {
    // Primary Theme
    readonly property color primary: "#4A9EFF"
    readonly property color secondary: "#3A8EEF"
    readonly property color background: "#0A0A0A"
    readonly property color surface: "#0A0A0A"
    readonly property color surfaceVariant: "#0F3460"
    readonly property color onBackground: "#EEEEEE"
    readonly property color onSurface: "#EEEEEE"
    readonly property color onSurfaceVariant: "#888888"
    readonly property color error: "#FF4757"

    // Status Colors
    readonly property color success: "#2ECC71"
    readonly property color warning: "#F39C12"
    readonly property color info: "#3498DB"

    // Provider State
    readonly property color stateOk: success
    readonly property color stateError: error
    readonly property color stateNotUsed: onSurfaceVariant
    readonly property color stateInactive: "#555555"

    // Container
    readonly property color cardBackground: "#121215"
    readonly property color cardBorder: "#222228"

    // Fields
    readonly property color fieldBackground: "#15151A"
    readonly property color fieldBorder: "#2A2A35"

    // Text
    readonly property color textPrimary: onBackground
    readonly property color textSecondary: onSurfaceVariant
    readonly property color textTertiary: "#666666"
    readonly property color textHint: "#555555"

    function colorForState(state) {
        switch (state) {
        case "ok": return stateOk
        case "error": return stateError
        case "inactive": return stateInactive
        default: return stateNotUsed
        }
    }
}
