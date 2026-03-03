// SettingsPreferences.h - Load/save settings to JSON files on disk (Linux/Qt6 port)
// Equivalent to Android SharedPreferences / macOS UserDefaults.

#pragma once

#include <QObject>
#include <QString>
#include <QJsonObject>
#include <QMap>
#include <QList>

#include "viewmodels/SettingsModels.h"

// ---------------------------------------------------------------------------
// GeneralSettings - user preferences and global API keys
// ---------------------------------------------------------------------------

struct GeneralSettings {
    QString userName = QStringLiteral("user");
    bool developerMode = true;
    QString huggingFaceApiKey;
    QString openRouterApiKey;
    QString defaultEmail;

    QJsonObject toJson() const;
    static GeneralSettings fromJson(const QJsonObject &obj);
    bool operator==(const GeneralSettings &) const = default;
};

// ---------------------------------------------------------------------------
// SettingsPreferences namespace - file-based JSON persistence
// ---------------------------------------------------------------------------

namespace SettingsPreferences {

// Main settings (agents, flocks, swarms, parameters, providers, etc.)
Settings loadSettings();
void saveSettings(const Settings &settings);

// General settings (user preferences, global API keys)
GeneralSettings loadGeneralSettings();
void saveGeneralSettings(const GeneralSettings &settings);

// Usage statistics (per provider::model)
QMap<QString, UsageStats> loadUsageStats();
void saveUsageStats(const QMap<QString, UsageStats> &stats);

// Prompt history (last 100 entries)
QList<PromptHistoryEntry> loadPromptHistory();
void savePromptHistory(const QList<PromptHistoryEntry> &history);
void addPromptHistoryEntry(const QString &title, const QString &prompt);

} // namespace SettingsPreferences
