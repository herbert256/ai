#include "AnalysisStreaming.h"
#include "ApiClient.h"
#include "AnalysisRepository.h"
#include <QJsonDocument>
#include <QJsonArray>
#include <QJsonObject>
#include <QDebug>

// -- Singleton --

AnalysisStreaming& AnalysisStreaming::instance() {
    static AnalysisStreaming s_instance;
    return s_instance;
}

AnalysisStreaming::AnalysisStreaming(QObject *parent)
    : QObject(parent)
{
}

// ---------------------------------------------------------------------------
// streamChat - dispatch by API format
// ---------------------------------------------------------------------------

void AnalysisStreaming::streamChat(const AppService &service, const QString &apiKey,
                                   const QString &model, const QList<ChatMessage> &messages,
                                   const ChatParameters &params, const QString &customBaseUrl) {
    stopCurrentStream();
    m_buffer.clear();
    m_currentEvent.clear();

    switch (service.apiFormat) {
    case ApiFormat::OpenAiCompatible:
        streamOpenAiCompatible(service, apiKey, model, messages, params, customBaseUrl);
        break;
    case ApiFormat::Anthropic:
        streamClaude(service, apiKey, model, messages, params);
        break;
    case ApiFormat::Google:
        streamGemini(service, apiKey, model, messages, params);
        break;
    }
}

// ---------------------------------------------------------------------------
// stopCurrentStream - abort any active streaming reply
// ---------------------------------------------------------------------------

void AnalysisStreaming::stopCurrentStream() {
    if (m_currentReply) {
        m_currentReply->disconnect(this);
        if (m_currentReply->isRunning())
            m_currentReply->abort();
        m_currentReply->deleteLater();
        m_currentReply = nullptr;
    }
}

// ---------------------------------------------------------------------------
// connectReply - wire up readyRead / finished / errorOccurred
// ---------------------------------------------------------------------------

void AnalysisStreaming::connectReply(QNetworkReply *reply,
                                     std::function<void(const QByteArray &line)> lineHandler) {
    m_currentReply = reply;

    // readyRead: accumulate bytes, split into lines, dispatch complete lines
    connect(reply, &QNetworkReply::readyRead, this, [this, lineHandler]() {
        m_buffer.append(m_currentReply->readAll());

        // Process all complete lines (terminated by \n)
        while (true) {
            int idx = m_buffer.indexOf('\n');
            if (idx < 0)
                break;

            QByteArray line = m_buffer.left(idx).trimmed();
            m_buffer.remove(0, idx + 1);

            if (!line.isEmpty())
                lineHandler(line);
        }
    });

    // finished: emit remaining buffer then signal completion
    connect(reply, &QNetworkReply::finished, this, [this, lineHandler, reply]() {
        // Process any remaining data in the buffer
        if (!m_buffer.isEmpty()) {
            QByteArray line = m_buffer.trimmed();
            m_buffer.clear();
            if (!line.isEmpty())
                lineHandler(line);
        }

        if (reply->error() != QNetworkReply::NoError &&
            reply->error() != QNetworkReply::OperationCanceledError) {
            emit streamError(reply->errorString());
        }

        reply->deleteLater();
        m_currentReply = nullptr;
        emit streamFinished();
    });

    // errorOccurred: emit error (finished will also fire after this)
    connect(reply, &QNetworkReply::errorOccurred, this,
            [this](QNetworkReply::NetworkError code) {
        Q_UNUSED(code)
        if (m_currentReply)
            emit streamError(m_currentReply->errorString());
    });
}

// ---------------------------------------------------------------------------
// streamOpenAiCompatible - OpenAI Chat Completions SSE
// ---------------------------------------------------------------------------

void AnalysisStreaming::streamOpenAiCompatible(const AppService &service, const QString &apiKey,
                                               const QString &model, const QList<ChatMessage> &messages,
                                               const ChatParameters &params, const QString &customBaseUrl) {
    // Check if this model uses the Responses API
    if (AnalysisRepository::usesResponsesApi(service, model)) {
        streamResponsesApi(service, apiKey, model, messages, params, customBaseUrl);
        return;
    }

    // Build messages array with system prompt
    QJsonArray jsonMessages = convertToOpenAiMessages(messages, params.systemPrompt);

    // Build request body
    QJsonObject body;
    body[QStringLiteral("model")] = model;
    body[QStringLiteral("messages")] = jsonMessages;
    body[QStringLiteral("stream")] = true;

    // Optional parameters
    if (params.temperature.has_value())
        body[QStringLiteral("temperature")] = static_cast<double>(*params.temperature);
    if (params.maxTokens.has_value())
        body[QStringLiteral("max_tokens")] = *params.maxTokens;
    if (params.topP.has_value())
        body[QStringLiteral("top_p")] = static_cast<double>(*params.topP);
    if (params.topK.has_value())
        body[QStringLiteral("top_k")] = *params.topK;
    if (params.frequencyPenalty.has_value())
        body[QStringLiteral("frequency_penalty")] = static_cast<double>(*params.frequencyPenalty);
    if (params.presencePenalty.has_value())
        body[QStringLiteral("presence_penalty")] = static_cast<double>(*params.presencePenalty);
    if (params.searchEnabled)
        body[QStringLiteral("search")] = true;
    if (service.supportsCitations && params.returnCitations)
        body[QStringLiteral("return_citations")] = true;
    if (service.supportsSearchRecency && params.searchRecency.has_value())
        body[QStringLiteral("search_recency_filter")] = *params.searchRecency;

    // POST to chat endpoint
    QString url = ApiClient::chatUrl(service, customBaseUrl);
    QNetworkReply *reply = ApiClient::instance().streamRequest(url, body, service, apiKey);

    connectReply(reply, [this](const QByteArray &line) {
        // SSE lines: "data: {...}" or "data: [DONE]"
        if (!line.startsWith("data:"))
            return;

        QByteArray data = line.mid(5).trimmed(); // strip "data:" prefix
        if (data == "[DONE]")
            return;

        QJsonParseError parseError;
        QJsonDocument doc = QJsonDocument::fromJson(data, &parseError);
        if (parseError.error != QJsonParseError::NoError || !doc.isObject())
            return;

        QJsonObject obj = doc.object();
        QJsonArray choices = obj[QStringLiteral("choices")].toArray();
        if (choices.isEmpty())
            return;

        QJsonObject delta = choices[0].toObject()[QStringLiteral("delta")].toObject();

        // Emit content text
        QString content = delta[QStringLiteral("content")].toString();
        if (!content.isEmpty())
            emit textChunk(content);

        // Emit reasoning_content (DeepSeek, etc.)
        QString reasoning = delta[QStringLiteral("reasoning_content")].toString();
        if (!reasoning.isEmpty())
            emit textChunk(reasoning);
    });
}

// ---------------------------------------------------------------------------
// streamResponsesApi - OpenAI Responses API SSE (gpt-5.x, o3, o4)
// ---------------------------------------------------------------------------

void AnalysisStreaming::streamResponsesApi(const AppService &service, const QString &apiKey,
                                            const QString &model, const QList<ChatMessage> &messages,
                                            const ChatParameters &params, const QString &customBaseUrl) {
    // Extract system prompt from messages
    QString systemPrompt = params.systemPrompt;
    if (systemPrompt.isEmpty()) {
        for (const auto &msg : messages) {
            if (msg.role == QStringLiteral("system")) {
                systemPrompt = msg.content;
                break;
            }
        }
    }

    // Build input messages (filter out system messages)
    QJsonArray inputMessages;
    for (const auto &msg : messages) {
        if (msg.role == QStringLiteral("system"))
            continue;
        QJsonObject msgObj;
        msgObj[QStringLiteral("role")] = msg.role;
        msgObj[QStringLiteral("content")] = msg.content;
        inputMessages.append(msgObj);
    }

    // Build request body
    QJsonObject body;
    body[QStringLiteral("model")] = model;
    body[QStringLiteral("input")] = inputMessages;
    body[QStringLiteral("stream")] = true;
    if (!systemPrompt.isEmpty())
        body[QStringLiteral("instructions")] = systemPrompt;

    // POST to responses endpoint
    const QString &base = customBaseUrl.isEmpty() ? service.baseUrl : customBaseUrl;
    QString normalizedBase = base.endsWith(QLatin1Char('/')) ? base : (base + QLatin1Char('/'));
    QString url = normalizedBase + QStringLiteral("v1/responses");
    QNetworkReply *reply = ApiClient::instance().streamRequest(url, body, service, apiKey);

    m_currentEvent.clear();

    connectReply(reply, [this](const QByteArray &line) {
        // Track event type
        if (line.startsWith("event:")) {
            m_currentEvent = QString::fromUtf8(line.mid(6).trimmed());
            return;
        }

        if (!line.startsWith("data:"))
            return;

        QByteArray data = line.mid(5).trimmed();
        if (data == "[DONE]")
            return;

        // Only process text delta events
        if (m_currentEvent != QStringLiteral("response.output_text.delta"))
            return;

        QJsonParseError parseError;
        QJsonDocument doc = QJsonDocument::fromJson(data, &parseError);
        if (parseError.error != QJsonParseError::NoError || !doc.isObject())
            return;

        QJsonObject obj = doc.object();
        QString delta = obj[QStringLiteral("delta")].toString();
        if (!delta.isEmpty())
            emit textChunk(delta);
    });
}

// ---------------------------------------------------------------------------
// streamClaude - Anthropic Messages API SSE
// ---------------------------------------------------------------------------

void AnalysisStreaming::streamClaude(const AppService &service, const QString &apiKey,
                                     const QString &model, const QList<ChatMessage> &messages,
                                     const ChatParameters &params) {
    // Extract system prompt
    QString systemPrompt = params.systemPrompt;
    if (systemPrompt.isEmpty()) {
        for (const auto &msg : messages) {
            if (msg.role == QStringLiteral("system")) {
                systemPrompt = msg.content;
                break;
            }
        }
    }

    // Build messages (filter out system)
    QJsonArray claudeMessages = convertToClaudeMessages(messages);

    // Build request body
    QJsonObject body;
    body[QStringLiteral("model")] = model;
    body[QStringLiteral("messages")] = claudeMessages;
    body[QStringLiteral("stream")] = true;
    body[QStringLiteral("max_tokens")] = params.maxTokens.value_or(4096);

    if (!systemPrompt.isEmpty())
        body[QStringLiteral("system")] = systemPrompt;
    if (params.temperature.has_value())
        body[QStringLiteral("temperature")] = static_cast<double>(*params.temperature);
    if (params.topP.has_value())
        body[QStringLiteral("top_p")] = static_cast<double>(*params.topP);
    if (params.topK.has_value())
        body[QStringLiteral("top_k")] = *params.topK;
    if (params.frequencyPenalty.has_value())
        body[QStringLiteral("frequency_penalty")] = static_cast<double>(*params.frequencyPenalty);
    if (params.presencePenalty.has_value())
        body[QStringLiteral("presence_penalty")] = static_cast<double>(*params.presencePenalty);
    if (params.searchEnabled)
        body[QStringLiteral("search")] = true;

    // POST to Claude messages endpoint
    QString normalizedBase = service.baseUrl.endsWith(QLatin1Char('/'))
        ? service.baseUrl : (service.baseUrl + QLatin1Char('/'));
    QString url = normalizedBase + QStringLiteral("v1/messages");
    QNetworkReply *reply = ApiClient::instance().streamRequest(url, body, service, apiKey);

    m_currentEvent.clear();

    connectReply(reply, [this](const QByteArray &line) {
        // Track event type
        if (line.startsWith("event:")) {
            m_currentEvent = QString::fromUtf8(line.mid(6).trimmed());
            return;
        }

        // Check for message_stop
        if (m_currentEvent == QStringLiteral("message_stop"))
            return;

        if (!line.startsWith("data:"))
            return;

        // Only process content_block_delta events
        if (m_currentEvent != QStringLiteral("content_block_delta"))
            return;

        QByteArray data = line.mid(5).trimmed();

        QJsonParseError parseError;
        QJsonDocument doc = QJsonDocument::fromJson(data, &parseError);
        if (parseError.error != QJsonParseError::NoError || !doc.isObject())
            return;

        QJsonObject obj = doc.object();
        QJsonObject delta = obj[QStringLiteral("delta")].toObject();
        QString text = delta[QStringLiteral("text")].toString();
        if (!text.isEmpty())
            emit textChunk(text);
    });
}

// ---------------------------------------------------------------------------
// streamGemini - Google Gemini GenerativeAI SSE
// ---------------------------------------------------------------------------

void AnalysisStreaming::streamGemini(const AppService &service, const QString &apiKey,
                                     const QString &model, const QList<ChatMessage> &messages,
                                     const ChatParameters &params) {
    // Build contents (filter system, map assistant->model)
    QJsonArray contents = convertToGeminiContents(messages);

    // Build generation config
    QJsonObject generationConfig;
    if (params.temperature.has_value())
        generationConfig[QStringLiteral("temperature")] = static_cast<double>(*params.temperature);
    if (params.maxTokens.has_value())
        generationConfig[QStringLiteral("maxOutputTokens")] = *params.maxTokens;
    if (params.topP.has_value())
        generationConfig[QStringLiteral("topP")] = static_cast<double>(*params.topP);
    if (params.topK.has_value())
        generationConfig[QStringLiteral("topK")] = *params.topK;

    // Build request body
    QJsonObject body;
    body[QStringLiteral("contents")] = contents;
    if (!generationConfig.isEmpty())
        body[QStringLiteral("generationConfig")] = generationConfig;

    // System instruction from messages or params
    QString systemPrompt = params.systemPrompt;
    if (systemPrompt.isEmpty()) {
        for (const auto &msg : messages) {
            if (msg.role == QStringLiteral("system")) {
                systemPrompt = msg.content;
                break;
            }
        }
    }
    if (!systemPrompt.isEmpty()) {
        QJsonObject systemInstruction;
        QJsonArray parts;
        QJsonObject textPart;
        textPart[QStringLiteral("text")] = systemPrompt;
        parts.append(textPart);
        systemInstruction[QStringLiteral("parts")] = parts;
        body[QStringLiteral("systemInstruction")] = systemInstruction;
    }

    if (params.searchEnabled) {
        QJsonObject searchTool;
        searchTool[QStringLiteral("google_search")] = QJsonObject();
        QJsonArray tools;
        tools.append(searchTool);
        body[QStringLiteral("tools")] = tools;
    }

    // POST to Gemini streaming endpoint
    QString url = ApiClient::geminiGenerateUrl(service.baseUrl, model, apiKey, /*stream=*/true);
    QNetworkReply *reply = ApiClient::instance().streamRequest(url, body, service, apiKey);

    connectReply(reply, [this](const QByteArray &line) {
        if (!line.startsWith("data:"))
            return;

        QByteArray data = line.mid(5).trimmed();

        QJsonParseError parseError;
        QJsonDocument doc = QJsonDocument::fromJson(data, &parseError);
        if (parseError.error != QJsonParseError::NoError || !doc.isObject())
            return;

        QJsonObject obj = doc.object();
        QJsonArray candidates = obj[QStringLiteral("candidates")].toArray();
        if (candidates.isEmpty())
            return;

        QJsonObject content = candidates[0].toObject()[QStringLiteral("content")].toObject();
        QJsonArray parts = content[QStringLiteral("parts")].toArray();
        if (parts.isEmpty())
            return;

        QString text = parts[0].toObject()[QStringLiteral("text")].toString();
        if (!text.isEmpty())
            emit textChunk(text);
    });
}

// ---------------------------------------------------------------------------
// Message conversion helpers
// ---------------------------------------------------------------------------

QJsonArray AnalysisStreaming::convertToOpenAiMessages(const QList<ChatMessage> &messages,
                                                      const QString &systemPrompt) {
    QJsonArray result;

    // Prepend system prompt if provided and not already in messages
    if (!systemPrompt.isEmpty()) {
        bool hasSystemMsg = false;
        for (const auto &msg : messages) {
            if (msg.role == QStringLiteral("system")) {
                hasSystemMsg = true;
                break;
            }
        }
        if (!hasSystemMsg) {
            QJsonObject sysMsg;
            sysMsg[QStringLiteral("role")] = QStringLiteral("system");
            sysMsg[QStringLiteral("content")] = systemPrompt;
            result.append(sysMsg);
        }
    }

    // Add all messages
    for (const auto &msg : messages) {
        QJsonObject obj;
        obj[QStringLiteral("role")] = msg.role;
        obj[QStringLiteral("content")] = msg.content;
        result.append(obj);
    }

    return result;
}

QJsonArray AnalysisStreaming::convertToClaudeMessages(const QList<ChatMessage> &messages) {
    QJsonArray result;
    for (const auto &msg : messages) {
        if (msg.role == QStringLiteral("system"))
            continue;
        QJsonObject obj;
        obj[QStringLiteral("role")] = msg.role;
        obj[QStringLiteral("content")] = msg.content;
        result.append(obj);
    }
    return result;
}

QJsonArray AnalysisStreaming::convertToGeminiContents(const QList<ChatMessage> &messages) {
    QJsonArray result;
    for (const auto &msg : messages) {
        if (msg.role == QStringLiteral("system"))
            continue;

        QJsonObject content;
        QJsonArray parts;
        QJsonObject textPart;
        textPart[QStringLiteral("text")] = msg.content;
        parts.append(textPart);
        content[QStringLiteral("parts")] = parts;

        // Map "assistant" -> "model" for Gemini
        if (msg.role == QStringLiteral("assistant"))
            content[QStringLiteral("role")] = QStringLiteral("model");
        else
            content[QStringLiteral("role")] = msg.role;

        result.append(content);
    }
    return result;
}
