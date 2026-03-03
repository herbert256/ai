// AnalysisChat.cpp - Non-streaming chat completions for all 3 API formats (Linux/Qt6 port)

#include "AnalysisChat.h"
#include "ApiClient.h"
#include "AnalysisRepository.h"
#include <QJsonDocument>
#include <QJsonArray>
#include <QJsonObject>
#include <QDebug>

// -- Singleton --

AnalysisChat& AnalysisChat::instance() {
    static AnalysisChat s_instance;
    return s_instance;
}

AnalysisChat::AnalysisChat(QObject *parent)
    : QObject(parent)
{
}

// -- Main dispatch --

void AnalysisChat::sendChatMessage(const AppService &service, const QString &apiKey,
                                    const QString &model, const QList<ChatMessage> &messages,
                                    const ChatParameters &params, const QString &customBaseUrl,
                                    std::function<void(QString response, QString error)> callback) {
    switch (service.apiFormat) {
    case ApiFormat::OpenAiCompatible:
        if (AnalysisRepository::usesResponsesApi(service, model)) {
            sendResponsesApi(service, apiKey, model, messages, params, customBaseUrl, callback);
        } else {
            sendOpenAiCompatible(service, apiKey, model, messages, params, customBaseUrl, callback);
        }
        break;
    case ApiFormat::Anthropic:
        sendClaude(service, apiKey, model, messages, params, callback);
        break;
    case ApiFormat::Google:
        sendGemini(service, apiKey, model, messages, params, callback);
        break;
    }
}

// -- OpenAI Compatible (Chat Completions) --

void AnalysisChat::sendOpenAiCompatible(const AppService &service, const QString &apiKey,
                                         const QString &model, const QList<ChatMessage> &messages,
                                         const ChatParameters &params, const QString &customBaseUrl,
                                         std::function<void(QString, QString)> callback) {
    // Build messages array
    QJsonArray msgsArray;

    // System message if provided
    if (!params.systemPrompt.isEmpty()) {
        QJsonObject sysMsg;
        sysMsg[QStringLiteral("role")] = QStringLiteral("system");
        sysMsg[QStringLiteral("content")] = params.systemPrompt;
        msgsArray.append(sysMsg);
    }

    // Conversation messages
    for (const auto &msg : messages) {
        if (msg.role == QStringLiteral("system"))
            continue; // Skip system messages from conversation; we use params.systemPrompt
        QJsonObject msgObj;
        msgObj[QStringLiteral("role")] = msg.role;
        msgObj[QStringLiteral("content")] = msg.content;
        msgsArray.append(msgObj);
    }

    // Build request body
    QJsonObject body;
    body[QStringLiteral("model")] = model;
    body[QStringLiteral("messages")] = msgsArray;

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

    QString url = ApiClient::chatUrl(service, customBaseUrl);

    ApiClient::instance().request(url, body, service, apiKey,
        [callback](const QJsonObject &json, int statusCode, const QString &headers) {
            Q_UNUSED(headers)

            // Check for error object in response
            if (json.contains(QStringLiteral("error"))) {
                QJsonObject errObj = json[QStringLiteral("error")].toObject();
                QString errMsg = errObj[QStringLiteral("message")].toString();
                if (errMsg.isEmpty())
                    errMsg = QStringLiteral("API error (status %1)").arg(statusCode);
                if (callback) callback(QString(), errMsg);
                return;
            }

            // Parse choices[0].message.content or reasoning_content
            QJsonArray choices = json[QStringLiteral("choices")].toArray();
            if (choices.isEmpty()) {
                if (callback) callback(QString(), QStringLiteral("No choices in response"));
                return;
            }

            QJsonObject message = choices[0].toObject()[QStringLiteral("message")].toObject();
            QString content = message[QStringLiteral("content")].toString();
            if (content.isEmpty())
                content = message[QStringLiteral("reasoning_content")].toString();

            if (content.isEmpty()) {
                if (callback) callback(QString(), QStringLiteral("No response content"));
                return;
            }

            if (callback) callback(content, QString());
        });
}

// -- OpenAI Responses API (gpt-5.x, o3, o4) --

void AnalysisChat::sendResponsesApi(const AppService &service, const QString &apiKey,
                                     const QString &model, const QList<ChatMessage> &messages,
                                     const ChatParameters &params, const QString &customBaseUrl,
                                     std::function<void(QString, QString)> callback) {
    // Extract system prompt as instructions
    QString instructions;
    if (!params.systemPrompt.isEmpty()) {
        instructions = params.systemPrompt;
    }

    // Filter out system messages from conversation
    QList<ChatMessage> inputMsgs;
    for (const auto &msg : messages) {
        if (msg.role != QStringLiteral("system"))
            inputMsgs.append(msg);
    }

    // Build request body
    QJsonObject body;
    body[QStringLiteral("model")] = model;

    // Single user message: input = string. Multi-turn: input = array of {role, content}
    if (inputMsgs.size() == 1 && inputMsgs.first().role == QStringLiteral("user")) {
        body[QStringLiteral("input")] = inputMsgs.first().content;
    } else {
        QJsonArray inputArray;
        for (const auto &msg : inputMsgs) {
            QJsonObject msgObj;
            msgObj[QStringLiteral("role")] = msg.role;
            msgObj[QStringLiteral("content")] = msg.content;
            inputArray.append(msgObj);
        }
        body[QStringLiteral("input")] = inputArray;
    }

    if (!instructions.isEmpty())
        body[QStringLiteral("instructions")] = instructions;

    // Build URL: {baseUrl}v1/responses
    const QString &base = customBaseUrl.isEmpty() ? service.baseUrl : customBaseUrl;
    QString normalizedBase = base.endsWith(QLatin1Char('/')) ? base : (base + QLatin1Char('/'));
    QString url = normalizedBase + QStringLiteral("v1/responses");

    ApiClient::instance().request(url, body, service, apiKey,
        [callback](const QJsonObject &json, int statusCode, const QString &headers) {
            Q_UNUSED(headers)

            // Check for error in response
            if (json.contains(QStringLiteral("error"))) {
                QJsonObject errObj = json[QStringLiteral("error")].toObject();
                QString errMsg = errObj[QStringLiteral("message")].toString();
                if (errMsg.isEmpty())
                    errMsg = QStringLiteral("Responses API error (status %1)").arg(statusCode);
                if (callback) callback(QString(), errMsg);
                return;
            }

            // Parse output array: find first item with content array,
            // then find first content block with type=="output_text"
            QJsonArray output = json[QStringLiteral("output")].toArray();
            QString text;

            for (const auto &item : output) {
                QJsonObject itemObj = item.toObject();
                QJsonArray contentArr = itemObj[QStringLiteral("content")].toArray();
                if (contentArr.isEmpty())
                    continue;

                // Look for output_text type first
                for (const auto &block : contentArr) {
                    QJsonObject blockObj = block.toObject();
                    if (blockObj[QStringLiteral("type")].toString() == QStringLiteral("output_text")) {
                        text = blockObj[QStringLiteral("text")].toString();
                        break;
                    }
                }
                if (!text.isEmpty()) break;

                // Fallback: first content block with text type
                for (const auto &block : contentArr) {
                    QJsonObject blockObj = block.toObject();
                    if (blockObj[QStringLiteral("type")].toString() == QStringLiteral("text")) {
                        text = blockObj[QStringLiteral("text")].toString();
                        break;
                    }
                }
                if (!text.isEmpty()) break;

                // Fallback: any block with a non-empty text field
                for (const auto &block : contentArr) {
                    QJsonObject blockObj = block.toObject();
                    QString t = blockObj[QStringLiteral("text")].toString();
                    if (!t.isEmpty()) {
                        text = t;
                        break;
                    }
                }
                if (!text.isEmpty()) break;
            }

            if (text.isEmpty()) {
                if (callback) callback(QString(), QStringLiteral("No response content"));
                return;
            }

            if (callback) callback(text, QString());
        });
}

// -- Anthropic Claude --

void AnalysisChat::sendClaude(const AppService &service, const QString &apiKey,
                               const QString &model, const QList<ChatMessage> &messages,
                               const ChatParameters &params,
                               std::function<void(QString, QString)> callback) {
    // Filter system messages from conversation, extract system prompt
    QJsonArray msgsArray;
    for (const auto &msg : messages) {
        if (msg.role == QStringLiteral("system"))
            continue;
        QJsonObject msgObj;
        msgObj[QStringLiteral("role")] = (msg.role == QStringLiteral("assistant"))
            ? QStringLiteral("assistant") : QStringLiteral("user");
        msgObj[QStringLiteral("content")] = msg.content;
        msgsArray.append(msgObj);
    }

    // Build request body
    QJsonObject body;
    body[QStringLiteral("model")] = model;
    body[QStringLiteral("max_tokens")] = params.maxTokens.value_or(4096); // Required for Anthropic
    body[QStringLiteral("messages")] = msgsArray;

    // System prompt
    if (!params.systemPrompt.isEmpty())
        body[QStringLiteral("system")] = params.systemPrompt;

    // Optional parameters
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

    // URL: {baseUrl}v1/messages
    QString normalizedBase = service.baseUrl.endsWith(QLatin1Char('/'))
        ? service.baseUrl : (service.baseUrl + QLatin1Char('/'));
    QString url = normalizedBase + QStringLiteral("v1/messages");

    ApiClient::instance().request(url, body, service, apiKey,
        [callback](const QJsonObject &json, int statusCode, const QString &headers) {
            Q_UNUSED(headers)

            // Check for error in response
            if (json.contains(QStringLiteral("error"))) {
                QJsonObject errObj = json[QStringLiteral("error")].toObject();
                QString errMsg = errObj[QStringLiteral("message")].toString();
                if (errMsg.isEmpty())
                    errMsg = QStringLiteral("Claude API error (status %1)").arg(statusCode);
                if (callback) callback(QString(), errMsg);
                return;
            }

            // Parse: content[0].text (first block with type "text")
            QJsonArray content = json[QStringLiteral("content")].toArray();
            QString text;

            for (const auto &block : content) {
                QJsonObject blockObj = block.toObject();
                if (blockObj[QStringLiteral("type")].toString() == QStringLiteral("text")) {
                    text = blockObj[QStringLiteral("text")].toString();
                    break;
                }
            }

            // Fallback: any block with a text field
            if (text.isEmpty()) {
                for (const auto &block : content) {
                    QJsonObject blockObj = block.toObject();
                    QString t = blockObj[QStringLiteral("text")].toString();
                    if (!t.isEmpty()) {
                        text = t;
                        break;
                    }
                }
            }

            if (text.isEmpty()) {
                if (callback) callback(QString(), QStringLiteral("No response content"));
                return;
            }

            if (callback) callback(text, QString());
        });
}

// -- Google Gemini --

void AnalysisChat::sendGemini(const AppService &service, const QString &apiKey,
                               const QString &model, const QList<ChatMessage> &messages,
                               const ChatParameters &params,
                               std::function<void(QString, QString)> callback) {
    // Build contents array, filtering system messages and mapping "assistant" -> "model"
    QJsonArray contents;
    for (const auto &msg : messages) {
        if (msg.role == QStringLiteral("system"))
            continue;

        QJsonObject partObj;
        partObj[QStringLiteral("text")] = msg.content;
        QJsonArray parts;
        parts.append(partObj);

        QJsonObject contentObj;
        contentObj[QStringLiteral("parts")] = parts;
        contentObj[QStringLiteral("role")] = (msg.role == QStringLiteral("assistant"))
            ? QStringLiteral("model") : QStringLiteral("user");
        contents.append(contentObj);
    }

    // Build request body
    QJsonObject body;
    body[QStringLiteral("contents")] = contents;

    // Generation config
    QJsonObject genConfig;
    if (params.temperature.has_value())
        genConfig[QStringLiteral("temperature")] = static_cast<double>(*params.temperature);
    if (params.topP.has_value())
        genConfig[QStringLiteral("topP")] = static_cast<double>(*params.topP);
    if (params.topK.has_value())
        genConfig[QStringLiteral("topK")] = *params.topK;
    if (params.maxTokens.has_value())
        genConfig[QStringLiteral("maxOutputTokens")] = *params.maxTokens;
    if (params.frequencyPenalty.has_value())
        genConfig[QStringLiteral("frequencyPenalty")] = static_cast<double>(*params.frequencyPenalty);
    if (params.presencePenalty.has_value())
        genConfig[QStringLiteral("presencePenalty")] = static_cast<double>(*params.presencePenalty);

    if (!genConfig.isEmpty())
        body[QStringLiteral("generationConfig")] = genConfig;

    // System instruction
    if (!params.systemPrompt.isEmpty()) {
        QJsonObject partObj;
        partObj[QStringLiteral("text")] = params.systemPrompt;
        QJsonArray parts;
        parts.append(partObj);

        QJsonObject sysInstruction;
        sysInstruction[QStringLiteral("parts")] = parts;
        body[QStringLiteral("systemInstruction")] = sysInstruction;
    }

    // URL: geminiGenerateUrl with stream=false
    QString url = ApiClient::geminiGenerateUrl(service.baseUrl, model, apiKey, /*stream=*/false);

    ApiClient::instance().request(url, body, service, apiKey,
        [callback](const QJsonObject &json, int statusCode, const QString &headers) {
            Q_UNUSED(headers)

            // Check for error in response
            if (json.contains(QStringLiteral("error"))) {
                QJsonObject errObj = json[QStringLiteral("error")].toObject();
                QString errMsg = errObj[QStringLiteral("message")].toString();
                if (errMsg.isEmpty())
                    errMsg = QStringLiteral("Gemini API error (status %1)").arg(statusCode);
                if (callback) callback(QString(), errMsg);
                return;
            }

            // Parse: candidates[0].content.parts[0].text
            QJsonArray candidates = json[QStringLiteral("candidates")].toArray();
            if (candidates.isEmpty()) {
                if (callback) callback(QString(), QStringLiteral("No candidates in response"));
                return;
            }

            QJsonObject firstCandidate = candidates[0].toObject();
            QJsonObject contentObj = firstCandidate[QStringLiteral("content")].toObject();
            QJsonArray parts = contentObj[QStringLiteral("parts")].toArray();

            QString text;
            if (!parts.isEmpty())
                text = parts[0].toObject()[QStringLiteral("text")].toString();

            // Fallback: any part with non-empty text
            if (text.isEmpty()) {
                for (const auto &part : parts) {
                    QString t = part.toObject()[QStringLiteral("text")].toString();
                    if (!t.isEmpty()) {
                        text = t;
                        break;
                    }
                }
            }

            if (text.isEmpty()) {
                if (callback) callback(QString(), QStringLiteral("No response content"));
                return;
            }

            if (callback) callback(text, QString());
        });
}
