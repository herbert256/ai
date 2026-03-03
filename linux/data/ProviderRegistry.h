#pragma once
#include <QObject>
#include <QMutex>
#include "AppService.h"

/// Mutable registry of AI service providers.
/// On first launch, loads from bundled qrc:/AI/resources/setup.json.
/// Subsequent launches load from ~/.local/share/AI/provider-registry.json.
/// Supports CRUD operations for fully data-driven provider management.
class ProviderRegistry : public QObject {
    Q_OBJECT
public:
    static ProviderRegistry& instance();

    void initialize(); // Load providers on startup

    QList<AppService> getAll();
    AppService* findById(const QString &id);

    void add(const AppService &service);
    void update(const AppService &service);
    void remove(const QString &id);
    void ensureProviders(const QList<AppService> &services); // Add only if ID not present
    void resetToDefaults();

private:
    explicit ProviderRegistry(QObject *parent = nullptr);

    void loadFromBundle();  // Load from qrc:/AI/resources/setup.json
    void loadFromStorage(); // Load from ~/.local/share/AI/provider-registry.json
    void save();            // Persist current state

    QMutex m_mutex;
    bool m_initialized = false;
    QString m_storagePath; // ~/.local/share/AI/provider-registry.json
};
