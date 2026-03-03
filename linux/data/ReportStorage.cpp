// ReportStorage.cpp - Thread-safe report persistence as individual JSON files

#include "ReportStorage.h"
#include <QDir>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMutexLocker>
#include <QStandardPaths>
#include <algorithm>

// ---------------------------------------------------------------------------
// Singleton
// ---------------------------------------------------------------------------

ReportStorage& ReportStorage::instance() {
    static ReportStorage storage;
    return storage;
}

ReportStorage::ReportStorage(QObject *parent)
    : QObject(parent)
    , m_storageDir(QStandardPaths::writableLocation(QStandardPaths::AppDataLocation)
                   + QStringLiteral("/reports/"))
{
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

QString ReportStorage::filePath(const QString &id) const {
    return m_storageDir + id + QStringLiteral(".json");
}

void ReportStorage::ensureDir() {
    QDir().mkpath(m_storageDir);
}

// ---------------------------------------------------------------------------
// Save
// ---------------------------------------------------------------------------

void ReportStorage::save(const StoredReport &report) {
    QMutexLocker locker(&m_mutex);
    ensureDir();

    const QJsonDocument doc(report.toJson());
    QFile file(filePath(report.id));
    if (file.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        file.write(doc.toJson(QJsonDocument::Indented));
        file.close();
    }
}

// ---------------------------------------------------------------------------
// Load single report
// ---------------------------------------------------------------------------

std::optional<StoredReport> ReportStorage::load(const QString &id) {
    QMutexLocker locker(&m_mutex);

    QFile file(filePath(id));
    if (!file.exists() || !file.open(QIODevice::ReadOnly))
        return std::nullopt;

    const QByteArray data = file.readAll();
    file.close();

    const QJsonDocument doc = QJsonDocument::fromJson(data);
    if (doc.isNull() || !doc.isObject())
        return std::nullopt;

    return StoredReport::fromJson(doc.object());
}

// ---------------------------------------------------------------------------
// Load all reports, sorted by timestamp descending
// ---------------------------------------------------------------------------

QList<StoredReport> ReportStorage::loadAll() {
    QMutexLocker locker(&m_mutex);
    ensureDir();

    QList<StoredReport> reports;
    const QDir dir(m_storageDir);
    const QStringList files = dir.entryList(
        QStringList() << QStringLiteral("*.json"), QDir::Files);

    for (const QString &fileName : files) {
        QFile file(m_storageDir + fileName);
        if (!file.open(QIODevice::ReadOnly))
            continue;

        const QByteArray data = file.readAll();
        file.close();

        const QJsonDocument doc = QJsonDocument::fromJson(data);
        if (doc.isNull() || !doc.isObject())
            continue;

        reports.append(StoredReport::fromJson(doc.object()));
    }

    // Sort by timestamp descending (newest first)
    std::sort(reports.begin(), reports.end(),
              [](const StoredReport &a, const StoredReport &b) {
                  return a.timestamp > b.timestamp;
              });

    return reports;
}

// ---------------------------------------------------------------------------
// Delete single report
// ---------------------------------------------------------------------------

void ReportStorage::deleteReport(const QString &id) {
    QMutexLocker locker(&m_mutex);
    QFile::remove(filePath(id));
}

// ---------------------------------------------------------------------------
// Delete all reports
// ---------------------------------------------------------------------------

void ReportStorage::deleteAll() {
    QMutexLocker locker(&m_mutex);

    const QDir dir(m_storageDir);
    const QStringList files = dir.entryList(
        QStringList() << QStringLiteral("*.json"), QDir::Files);

    for (const QString &fileName : files) {
        QFile::remove(m_storageDir + fileName);
    }
}

// ---------------------------------------------------------------------------
// Count reports
// ---------------------------------------------------------------------------

int ReportStorage::count() {
    QMutexLocker locker(&m_mutex);

    const QDir dir(m_storageDir);
    return dir.entryList(
        QStringList() << QStringLiteral("*.json"), QDir::Files).count();
}
