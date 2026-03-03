// ChatHistoryManager.h - Thread-safe chat session persistence (Linux/Qt6 port)

#pragma once

#include <QObject>
#include <QMutex>
#include <optional>
#include "DataModels.h"

class ChatHistoryManager : public QObject {
    Q_OBJECT

public:
    static ChatHistoryManager& instance();

    void save(const ChatSession &session);
    std::optional<ChatSession> load(const QString &id);
    QList<ChatSession> loadAll(); // sorted by updatedAt descending
    void deleteSession(const QString &id);
    void deleteAll();
    int count();
    QList<ChatSession> search(const QString &query); // case-insensitive search in message content

private:
    explicit ChatHistoryManager(QObject *parent = nullptr);
    Q_DISABLE_COPY_MOVE(ChatHistoryManager)

    QMutex m_mutex;
    QString m_storageDir; // ~/.local/share/AI/chat-history/

    QString filePath(const QString &id) const;
    void ensureDir();
};
