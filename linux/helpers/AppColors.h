#pragma once
#include <QColor>
#include <QString>

namespace AppColors {

// Primary Theme
inline constexpr QColor primary()         { return QColor(0x4A, 0x9E, 0xFF); }
inline constexpr QColor secondary()       { return QColor(0x3A, 0x8E, 0xEF); }
inline constexpr QColor background()      { return QColor(0x0A, 0x0A, 0x0A); }
inline constexpr QColor surface()         { return QColor(0x0A, 0x0A, 0x0A); }
inline constexpr QColor surfaceVariant()  { return QColor(0x0F, 0x34, 0x60); }
inline constexpr QColor onBackground()    { return QColor(0xEE, 0xEE, 0xEE); }
inline constexpr QColor onSurface()       { return QColor(0xEE, 0xEE, 0xEE); }
inline constexpr QColor onSurfaceVariant(){ return QColor(0x88, 0x88, 0x88); }
inline constexpr QColor error()           { return QColor(0xFF, 0x47, 0x57); }

// Status Colors
inline constexpr QColor success()         { return QColor(0x2E, 0xCC, 0x71); }
inline constexpr QColor warning()         { return QColor(0xF3, 0x9C, 0x12); }
inline constexpr QColor info()            { return QColor(0x34, 0x98, 0xDB); }

// Provider State
inline constexpr QColor stateOk()         { return success(); }
inline constexpr QColor stateError()      { return error(); }
inline constexpr QColor stateNotUsed()    { return onSurfaceVariant(); }
inline constexpr QColor stateInactive()   { return QColor(0x55, 0x55, 0x55); }

// Container
inline constexpr QColor cardBackground()  { return QColor(0x12, 0x12, 0x15); }
inline constexpr QColor cardBorder()      { return QColor(0x22, 0x22, 0x28); }

// Fields
inline constexpr QColor fieldBackground() { return QColor(0x15, 0x15, 0x1A); }
inline constexpr QColor fieldBorder()     { return QColor(0x2A, 0x2A, 0x35); }

// Text
inline constexpr QColor textPrimary()     { return onBackground(); }
inline constexpr QColor textSecondary()   { return onSurfaceVariant(); }
inline constexpr QColor textTertiary()    { return QColor(0x66, 0x66, 0x66); }
inline constexpr QColor textHint()        { return QColor(0x55, 0x55, 0x55); }

inline QColor colorForState(const QString &state) {
    if (state == "ok")       return stateOk();
    if (state == "error")    return stateError();
    if (state == "inactive") return stateInactive();
    return stateNotUsed();
}

} // namespace AppColors
