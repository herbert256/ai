#include "ProviderRegistry.h"
#include <QFile>
#include <QDir>
#include <QStandardPaths>
#include <QJsonDocument>
#include <QJsonArray>
#include <QJsonObject>
#include <QDebug>

static const char *TAG = "ProviderRegistry";

// ---------------------------------------------------------------------------
// Singleton
// ---------------------------------------------------------------------------

ProviderRegistry& ProviderRegistry::instance() {
    static ProviderRegistry inst;
    return inst;
}

ProviderRegistry::ProviderRegistry(QObject *parent)
    : QObject(parent)
{
    const QString dataDir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    m_storagePath = dataDir + QStringLiteral("/provider-registry.json");
}

// ---------------------------------------------------------------------------
// Initialization
// ---------------------------------------------------------------------------

void ProviderRegistry::initialize() {
    QMutexLocker locker(&m_mutex);
    if (m_initialized) return;

    // Try loading persisted providers first
    loadFromStorage();

    // If nothing was loaded (first launch or corrupt file), load from bundle
    if (AppService::entries.isEmpty()) {
        loadFromBundle();
    }

    // Safety net: if still empty after both attempts, log a warning
    if (AppService::entries.isEmpty()) {
        qWarning() << TAG << "No providers loaded from storage or bundle";
    }

    m_initialized = true;
    qDebug() << TAG << "Initialized with" << AppService::entries.size() << "providers";
}

// ---------------------------------------------------------------------------
// Bundle loading (qrc:/AI/resources/setup.json)
// ---------------------------------------------------------------------------

void ProviderRegistry::loadFromBundle() {
    QFile file(QStringLiteral(":/AI/resources/setup.json"));
    if (!file.open(QIODevice::ReadOnly | QIODevice::Text)) {
        qWarning() << TAG << "setup.json not found in bundle resources";
        return;
    }

    const QByteArray data = file.readAll();
    file.close();

    QJsonParseError parseError;
    const QJsonDocument doc = QJsonDocument::fromJson(data, &parseError);
    if (parseError.error != QJsonParseError::NoError) {
        qWarning() << TAG << "Error parsing setup.json:" << parseError.errorString();
        return;
    }

    const QJsonObject root = doc.object();
    const QJsonArray defsArray = root[QStringLiteral("providerDefinitions")].toArray();
    if (defsArray.isEmpty()) {
        qWarning() << TAG << "No providerDefinitions in setup.json";
        return;
    }

    QList<AppService> list;
    list.reserve(defsArray.size());
    for (const QJsonValue &val : defsArray) {
        const ProviderDefinition pd = ProviderDefinition::fromJson(val.toObject());
        list.append(pd.toAppService());
    }

    AppService::entries = list;
    save();
    qDebug() << TAG << "Loaded" << list.size() << "providers from bundle setup.json";
}

// ---------------------------------------------------------------------------
// Storage loading (~/.local/share/AI/provider-registry.json)
// ---------------------------------------------------------------------------

void ProviderRegistry::loadFromStorage() {
    QFile file(m_storagePath);
    if (!file.exists()) return;

    if (!file.open(QIODevice::ReadOnly | QIODevice::Text)) {
        qWarning() << TAG << "Cannot open storage file:" << m_storagePath;
        return;
    }

    const QByteArray data = file.readAll();
    file.close();

    QJsonParseError parseError;
    const QJsonDocument doc = QJsonDocument::fromJson(data, &parseError);
    if (parseError.error != QJsonParseError::NoError) {
        qWarning() << TAG << "Error parsing storage file:" << parseError.errorString();
        return;
    }

    const QJsonObject root = doc.object();
    const QJsonArray defsArray = root[QStringLiteral("providerDefinitions")].toArray();
    if (defsArray.isEmpty()) return;

    QList<AppService> list;
    list.reserve(defsArray.size());
    for (const QJsonValue &val : defsArray) {
        const ProviderDefinition pd = ProviderDefinition::fromJson(val.toObject());
        list.append(pd.toAppService());
    }

    AppService::entries = list;
    qDebug() << TAG << "Loaded" << list.size() << "providers from storage";
}

// ---------------------------------------------------------------------------
// Persistence
// ---------------------------------------------------------------------------

void ProviderRegistry::save() {
    // Ensure the storage directory exists
    const QFileInfo fi(m_storagePath);
    QDir().mkpath(fi.absolutePath());

    // Convert entries to ProviderDefinition JSON array
    QJsonArray defsArray;
    for (const AppService &s : AppService::entries) {
        const ProviderDefinition pd = ProviderDefinition::fromAppService(s);
        defsArray.append(pd.toJson());
    }

    QJsonObject root;
    root[QStringLiteral("providerDefinitions")] = defsArray;

    const QJsonDocument doc(root);
    QFile file(m_storagePath);
    if (!file.open(QIODevice::WriteOnly | QIODevice::Text)) {
        qWarning() << TAG << "Cannot write storage file:" << m_storagePath;
        return;
    }

    file.write(doc.toJson(QJsonDocument::Indented));
    file.close();
}

// ---------------------------------------------------------------------------
// Accessors
// ---------------------------------------------------------------------------

QList<AppService> ProviderRegistry::getAll() {
    QMutexLocker locker(&m_mutex);
    return AppService::entries;
}

AppService* ProviderRegistry::findById(const QString &id) {
    QMutexLocker locker(&m_mutex);
    return AppService::findById(id);
}

// ---------------------------------------------------------------------------
// CRUD operations
// ---------------------------------------------------------------------------

void ProviderRegistry::add(const AppService &service) {
    QMutexLocker locker(&m_mutex);
    AppService::entries.append(service);
    save();
}

void ProviderRegistry::update(const AppService &service) {
    QMutexLocker locker(&m_mutex);
    for (int i = 0; i < AppService::entries.size(); ++i) {
        if (AppService::entries[i].id == service.id) {
            AppService::entries[i] = service;
            save();
            return;
        }
    }
}

void ProviderRegistry::remove(const QString &id) {
    QMutexLocker locker(&m_mutex);
    AppService::entries.removeIf([&id](const AppService &s) {
        return s.id == id;
    });
    save();
}

void ProviderRegistry::ensureProviders(const QList<AppService> &services) {
    QMutexLocker locker(&m_mutex);
    bool changed = false;
    for (const AppService &service : services) {
        if (!AppService::findById(service.id)) {
            AppService::entries.append(service);
            changed = true;
        }
    }
    if (changed) save();
}

void ProviderRegistry::resetToDefaults() {
    QMutexLocker locker(&m_mutex);
    m_initialized = false;
    AppService::entries.clear();
    loadFromBundle();
    m_initialized = true;
}
