// ReportStorage.h - Thread-safe report persistence as individual JSON files
// Storage directory: ~/.local/share/AI/reports/

#pragma once

#include <QObject>
#include <QMutex>
#include "DataModels.h"

class ReportStorage : public QObject {
    Q_OBJECT
public:
    static ReportStorage& instance();

    void save(const StoredReport &report);
    std::optional<StoredReport> load(const QString &id);
    QList<StoredReport> loadAll(); // sorted by timestamp descending
    void deleteReport(const QString &id);
    void deleteAll();
    int count();

private:
    explicit ReportStorage(QObject *parent = nullptr);
    QMutex m_mutex;
    QString m_storageDir; // ~/.local/share/AI/reports/

    QString filePath(const QString &id) const;
    void ensureDir();
};
