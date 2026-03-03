// AnalysisChat.h - Non-streaming chat completions for all 3 API formats (Linux/Qt6 port)

#pragma once
#include <QObject>
#include <functional>
#include "DataModels.h"
#include "AppService.h"

class AnalysisChat : public QObject {
    Q_OBJECT
public:
    static AnalysisChat& instance();

    // Send a single chat message (non-streaming), callback receives response text or error
    void sendChatMessage(const AppService &service, const QString &apiKey,
                         const QString &model, const QList<ChatMessage> &messages,
                         const ChatParameters &params, const QString &customBaseUrl,
                         std::function<void(QString response, QString error)> callback);

private:
    explicit AnalysisChat(QObject *parent = nullptr);

    void sendOpenAiCompatible(const AppService &service, const QString &apiKey,
                               const QString &model, const QList<ChatMessage> &messages,
                               const ChatParameters &params, const QString &customBaseUrl,
                               std::function<void(QString, QString)> callback);
    void sendResponsesApi(const AppService &service, const QString &apiKey,
                           const QString &model, const QList<ChatMessage> &messages,
                           const ChatParameters &params, const QString &customBaseUrl,
                           std::function<void(QString, QString)> callback);
    void sendClaude(const AppService &service, const QString &apiKey,
                     const QString &model, const QList<ChatMessage> &messages,
                     const ChatParameters &params,
                     std::function<void(QString, QString)> callback);
    void sendGemini(const AppService &service, const QString &apiKey,
                     const QString &model, const QList<ChatMessage> &messages,
                     const ChatParameters &params,
                     std::function<void(QString, QString)> callback);
};
