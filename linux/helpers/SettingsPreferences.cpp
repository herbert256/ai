// SettingsPreferences.cpp - Load/save settings to JSON files on disk (Linux/Qt6 port)
//
// Storage location: ~/.local/share/AI/ (QStandardPaths::AppDataLocation)
//
// File mapping:
//   settings.json       -> Settings
//   general.json        -> GeneralSettings
//   usage-stats.json    -> QMap<QString, UsageStats>
//   prompt-history.json -> QList<PromptHistoryEntry>

#include "helpers/SettingsPreferences.h"

#include <QDir>
#include <QFile>
#include <QJsonDocument>
#include <QJsonArray>
#include <QStandardPaths>
#include <QUuid>
#include <QDateTime>

// ---------------------------------------------------------------------------
// Helper: storage directory
// ---------------------------------------------------------------------------

static QString storagePath()
{
    return QStandardPaths::writableLocation(QStandardPaths::AppDataLocation)
           + QStringLiteral("/");
}

static bool ensureStorageDir()
{
    return QDir().mkpath(storagePath());
}

// ---------------------------------------------------------------------------
// Helper: read / write JSON files
// ---------------------------------------------------------------------------

static QJsonObject readJsonObject(const QString &filename)
{
    QFile file(storagePath() + filename);
    if (!file.open(QIODevice::ReadOnly))
        return {};
    const QByteArray data = file.readAll();
    file.close();

    QJsonParseError error;
    const QJsonDocument doc = QJsonDocument::fromJson(data, &error);
    if (error.error != QJsonParseError::NoError || !doc.isObject())
        return {};
    return doc.object();
}

static QJsonArray readJsonArray(const QString &filename)
{
    QFile file(storagePath() + filename);
    if (!file.open(QIODevice::ReadOnly))
        return {};
    const QByteArray data = file.readAll();
    file.close();

    QJsonParseError error;
    const QJsonDocument doc = QJsonDocument::fromJson(data, &error);
    if (error.error != QJsonParseError::NoError || !doc.isArray())
        return {};
    return doc.array();
}

static void writeJson(const QString &filename, const QJsonDocument &doc)
{
    if (!ensureStorageDir())
        return;
    QFile file(storagePath() + filename);
    if (!file.open(QIODevice::WriteOnly | QIODevice::Truncate))
        return;
    file.write(doc.toJson(QJsonDocument::Indented));
    file.close();
}

// ---------------------------------------------------------------------------
// GeneralSettings serialization
// ---------------------------------------------------------------------------

QJsonObject GeneralSettings::toJson() const
{
    QJsonObject obj;
    obj[QStringLiteral("userName")]          = userName;
    obj[QStringLiteral("developerMode")]     = developerMode;
    obj[QStringLiteral("huggingFaceApiKey")] = huggingFaceApiKey;
    obj[QStringLiteral("openRouterApiKey")]  = openRouterApiKey;
    obj[QStringLiteral("defaultEmail")]      = defaultEmail;
    return obj;
}

GeneralSettings GeneralSettings::fromJson(const QJsonObject &obj)
{
    GeneralSettings s;
    s.userName          = obj[QStringLiteral("userName")].toString(QStringLiteral("user"));
    s.developerMode     = obj[QStringLiteral("developerMode")].toBool(true);
    s.huggingFaceApiKey = obj[QStringLiteral("huggingFaceApiKey")].toString();
    s.openRouterApiKey  = obj[QStringLiteral("openRouterApiKey")].toString();
    s.defaultEmail      = obj[QStringLiteral("defaultEmail")].toString();
    return s;
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

Settings SettingsPreferences::loadSettings()
{
    const QJsonObject obj = readJsonObject(QStringLiteral("settings.json"));
    if (obj.isEmpty())
        return Settings();
    return Settings::fromJson(obj);
}

void SettingsPreferences::saveSettings(const Settings &settings)
{
    writeJson(QStringLiteral("settings.json"),
              QJsonDocument(settings.toJson()));
}

// ---------------------------------------------------------------------------
// GeneralSettings
// ---------------------------------------------------------------------------

GeneralSettings SettingsPreferences::loadGeneralSettings()
{
    const QJsonObject obj = readJsonObject(QStringLiteral("general.json"));
    if (obj.isEmpty())
        return GeneralSettings();
    return GeneralSettings::fromJson(obj);
}

void SettingsPreferences::saveGeneralSettings(const GeneralSettings &settings)
{
    writeJson(QStringLiteral("general.json"),
              QJsonDocument(settings.toJson()));
}

// ---------------------------------------------------------------------------
// Usage Stats
// ---------------------------------------------------------------------------

QMap<QString, UsageStats> SettingsPreferences::loadUsageStats()
{
    const QJsonObject obj = readJsonObject(QStringLiteral("usage-stats.json"));
    if (obj.isEmpty())
        return {};

    QMap<QString, UsageStats> stats;
    for (auto it = obj.constBegin(); it != obj.constEnd(); ++it) {
        stats.insert(it.key(), UsageStats::fromJson(it.value().toObject()));
    }
    return stats;
}

void SettingsPreferences::saveUsageStats(const QMap<QString, UsageStats> &stats)
{
    QJsonObject obj;
    for (auto it = stats.constBegin(); it != stats.constEnd(); ++it) {
        obj.insert(it.key(), it.value().toJson());
    }
    writeJson(QStringLiteral("usage-stats.json"), QJsonDocument(obj));
}

// ---------------------------------------------------------------------------
// Prompt History
// ---------------------------------------------------------------------------

QList<PromptHistoryEntry> SettingsPreferences::loadPromptHistory()
{
    const QJsonArray arr = readJsonArray(QStringLiteral("prompt-history.json"));
    if (arr.isEmpty())
        return {};

    QList<PromptHistoryEntry> history;
    history.reserve(arr.size());
    for (const auto &v : arr) {
        history.append(PromptHistoryEntry::fromJson(v.toObject()));
    }
    return history;
}

void SettingsPreferences::savePromptHistory(const QList<PromptHistoryEntry> &history)
{
    QJsonArray arr;
    for (const auto &entry : history) {
        arr.append(entry.toJson());
    }
    writeJson(QStringLiteral("prompt-history.json"), QJsonDocument(arr));
}

void SettingsPreferences::addPromptHistoryEntry(const QString &title, const QString &prompt)
{
    QList<PromptHistoryEntry> history = loadPromptHistory();

    PromptHistoryEntry entry;
    entry.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    entry.timestamp = QDateTime::currentDateTimeUtc();
    entry.title = title;
    entry.prompt = prompt;

    history.prepend(entry);

    // Keep last 100 entries
    if (history.size() > 100)
        history = history.mid(0, 100);

    savePromptHistory(history);
}
