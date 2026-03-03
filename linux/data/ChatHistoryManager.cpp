// ChatHistoryManager.cpp - Thread-safe chat session persistence (Linux/Qt6 port)

#include "ChatHistoryManager.h"

#include <QStandardPaths>
#include <QDir>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMutexLocker>

#include <algorithm>

// ---------------------------------------------------------------------------
// Singleton
// ---------------------------------------------------------------------------

ChatHistoryManager& ChatHistoryManager::instance()
{
    static ChatHistoryManager mgr;
    return mgr;
}

// ---------------------------------------------------------------------------
// Constructor
// ---------------------------------------------------------------------------

ChatHistoryManager::ChatHistoryManager(QObject *parent)
    : QObject(parent)
{
    m_storageDir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation)
                   + QStringLiteral("/chat-history/");
    ensureDir();
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

QString ChatHistoryManager::filePath(const QString &id) const
{
    return m_storageDir + id + QStringLiteral(".json");
}

void ChatHistoryManager::ensureDir()
{
    QDir().mkpath(m_storageDir);
}

// ---------------------------------------------------------------------------
// save
// ---------------------------------------------------------------------------

void ChatHistoryManager::save(const ChatSession &session)
{
    QMutexLocker locker(&m_mutex);
    ensureDir();

    QJsonDocument doc(session.toJson());
    QFile file(filePath(session.id));
    if (file.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        file.write(doc.toJson(QJsonDocument::Indented));
        file.close();
    }
}

// ---------------------------------------------------------------------------
// load
// ---------------------------------------------------------------------------

std::optional<ChatSession> ChatHistoryManager::load(const QString &id)
{
    QMutexLocker locker(&m_mutex);

    QFile file(filePath(id));
    if (!file.exists() || !file.open(QIODevice::ReadOnly)) {
        return std::nullopt;
    }

    QJsonParseError err;
    QJsonDocument doc = QJsonDocument::fromJson(file.readAll(), &err);
    file.close();

    if (err.error != QJsonParseError::NoError || !doc.isObject()) {
        return std::nullopt;
    }

    return ChatSession::fromJson(doc.object());
}

// ---------------------------------------------------------------------------
// loadAll  (sorted by updatedAt descending)
// ---------------------------------------------------------------------------

QList<ChatSession> ChatHistoryManager::loadAll()
{
    QMutexLocker locker(&m_mutex);

    QDir dir(m_storageDir);
    if (!dir.exists()) {
        return {};
    }

    const QStringList files = dir.entryList(
        QStringList() << QStringLiteral("*.json"), QDir::Files);

    QList<ChatSession> sessions;
    sessions.reserve(files.size());

    for (const QString &fileName : files) {
        QFile file(m_storageDir + fileName);
        if (!file.open(QIODevice::ReadOnly)) {
            continue;
        }
        QJsonParseError err;
        QJsonDocument doc = QJsonDocument::fromJson(file.readAll(), &err);
        file.close();

        if (err.error != QJsonParseError::NoError || !doc.isObject()) {
            continue;
        }
        sessions.append(ChatSession::fromJson(doc.object()));
    }

    std::sort(sessions.begin(), sessions.end(),
              [](const ChatSession &a, const ChatSession &b) {
                  return a.updatedAt > b.updatedAt;
              });

    return sessions;
}

// ---------------------------------------------------------------------------
// deleteSession
// ---------------------------------------------------------------------------

void ChatHistoryManager::deleteSession(const QString &id)
{
    QMutexLocker locker(&m_mutex);
    QFile::remove(filePath(id));
}

// ---------------------------------------------------------------------------
// deleteAll
// ---------------------------------------------------------------------------

void ChatHistoryManager::deleteAll()
{
    QMutexLocker locker(&m_mutex);

    QDir dir(m_storageDir);
    if (!dir.exists()) {
        return;
    }

    const QStringList files = dir.entryList(
        QStringList() << QStringLiteral("*.json"), QDir::Files);

    for (const QString &fileName : files) {
        QFile::remove(m_storageDir + fileName);
    }
}

// ---------------------------------------------------------------------------
// count
// ---------------------------------------------------------------------------

int ChatHistoryManager::count()
{
    QMutexLocker locker(&m_mutex);

    QDir dir(m_storageDir);
    if (!dir.exists()) {
        return 0;
    }

    return dir.entryList(
        QStringList() << QStringLiteral("*.json"), QDir::Files).size();
}

// ---------------------------------------------------------------------------
// search  (case-insensitive content search, sorted by updatedAt descending)
// ---------------------------------------------------------------------------

QList<ChatSession> ChatHistoryManager::search(const QString &query)
{
    QMutexLocker locker(&m_mutex);

    QDir dir(m_storageDir);
    if (!dir.exists()) {
        return {};
    }

    const QStringList files = dir.entryList(
        QStringList() << QStringLiteral("*.json"), QDir::Files);

    QList<ChatSession> results;

    for (const QString &fileName : files) {
        QFile file(m_storageDir + fileName);
        if (!file.open(QIODevice::ReadOnly)) {
            continue;
        }
        QJsonParseError err;
        QJsonDocument doc = QJsonDocument::fromJson(file.readAll(), &err);
        file.close();

        if (err.error != QJsonParseError::NoError || !doc.isObject()) {
            continue;
        }

        ChatSession session = ChatSession::fromJson(doc.object());

        bool matches = false;
        for (const ChatMessage &msg : session.messages) {
            if (msg.content.contains(query, Qt::CaseInsensitive)) {
                matches = true;
                break;
            }
        }

        if (matches) {
            results.append(std::move(session));
        }
    }

    std::sort(results.begin(), results.end(),
              [](const ChatSession &a, const ChatSession &b) {
                  return a.updatedAt > b.updatedAt;
              });

    return results;
}
