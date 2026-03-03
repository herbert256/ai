#pragma once
#include <QObject>
#include <QNetworkReply>
#include <QJsonArray>
#include <functional>
#include "DataModels.h"
#include "AppService.h"

class AnalysisStreaming : public QObject {
    Q_OBJECT
public:
    static AnalysisStreaming& instance();

    // Start streaming chat - returns immediately, emits signals as chunks arrive.
    // Caller must connect to textChunk, streamError, streamFinished signals.
    void streamChat(const AppService &service, const QString &apiKey,
                    const QString &model, const QList<ChatMessage> &messages,
                    const ChatParameters &params, const QString &customBaseUrl = "");

signals:
    void textChunk(const QString &text);
    void streamError(const QString &error);
    void streamFinished();

private:
    explicit AnalysisStreaming(QObject *parent = nullptr);

    QNetworkReply *m_currentReply = nullptr;
    QByteArray m_buffer; // accumulated bytes between readyRead calls
    QString m_currentEvent; // for Claude/Responses API event: tracking

    void stopCurrentStream();

    // Format-specific streaming
    void streamOpenAiCompatible(const AppService &service, const QString &apiKey,
                                const QString &model, const QList<ChatMessage> &messages,
                                const ChatParameters &params, const QString &customBaseUrl);
    void streamResponsesApi(const AppService &service, const QString &apiKey,
                            const QString &model, const QList<ChatMessage> &messages,
                            const ChatParameters &params, const QString &customBaseUrl);
    void streamClaude(const AppService &service, const QString &apiKey,
                      const QString &model, const QList<ChatMessage> &messages,
                      const ChatParameters &params);
    void streamGemini(const AppService &service, const QString &apiKey,
                      const QString &model, const QList<ChatMessage> &messages,
                      const ChatParameters &params);

    // Connect readyRead + finished to the reply
    void connectReply(QNetworkReply *reply, std::function<void(const QByteArray &line)> lineHandler);

    // Build OpenAI messages array from ChatMessage list
    QJsonArray convertToOpenAiMessages(const QList<ChatMessage> &messages, const QString &systemPrompt = "");

    // Build Claude messages (filter out system role)
    QJsonArray convertToClaudeMessages(const QList<ChatMessage> &messages);

    // Build Gemini contents (filter system, map assistant->model)
    QJsonArray convertToGeminiContents(const QList<ChatMessage> &messages);
};
