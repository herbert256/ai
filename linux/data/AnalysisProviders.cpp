// AnalysisProviders.cpp - Non-streaming analysis dispatch for reports (Linux/Qt6 port)
// Handles OpenAI-compatible (28 providers), Anthropic Claude, and Google Gemini formats.

#include "AnalysisProviders.h"
#include "ApiClient.h"
#include "AnalysisRepository.h"
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QDebug>

// ---------------------------------------------------------------------------
// Singleton
// ---------------------------------------------------------------------------

AnalysisProviders& AnalysisProviders::instance() {
    static AnalysisProviders s_instance;
    return s_instance;
}

AnalysisProviders::AnalysisProviders(QObject *parent)
    : QObject(parent)
{
}

// ---------------------------------------------------------------------------
// Main entry point - dispatch by apiFormat
// ---------------------------------------------------------------------------

void AnalysisProviders::analyzeWithAgent(const QString &serviceId, const QString &apiKey,
                                          const QString &model, const QString &prompt,
                                          const AgentParameters &params,
                                          const QString &agentName,
                                          const QString &customBaseUrl,
                                          std::function<void(AnalysisResponse)> callback)
{
    AppService *servicePtr = AppService::findById(serviceId);
    if (!servicePtr) {
        AnalysisResponse resp(serviceId);
        resp.error = QStringLiteral("Provider not found: %1").arg(serviceId);
        resp.agentName = agentName;
        if (callback) callback(resp);
        return;
    }

    const AppService &service = *servicePtr;

    // Wrap callback to set agentName on all responses
    auto wrappedCallback = [agentName, callback](AnalysisResponse resp) {
        resp.agentName = agentName;
        if (callback) callback(resp);
    };

    switch (service.apiFormat) {
    case ApiFormat::OpenAiCompatible:
        analyzeOpenAiCompatible(service, apiKey, prompt, model, params, customBaseUrl, wrappedCallback);
        break;
    case ApiFormat::Anthropic:
        analyzeClaude(service, apiKey, prompt, model, params, wrappedCallback);
        break;
    case ApiFormat::Google:
        analyzeGemini(service, apiKey, prompt, model, params, wrappedCallback);
        break;
    }
}

// ---------------------------------------------------------------------------
// OpenAI-compatible (28 providers, Chat Completions API)
// ---------------------------------------------------------------------------

void AnalysisProviders::analyzeOpenAiCompatible(const AppService &service, const QString &apiKey,
                                                  const QString &prompt, const QString &model,
                                                  const AgentParameters &params,
                                                  const QString &customBaseUrl,
                                                  std::function<void(AnalysisResponse)> callback)
{
    // Check if this model uses the Responses API (OpenAI gpt-5.x, o3, o4)
    if (AnalysisRepository::usesResponsesApi(service, model)) {
        analyzeResponsesApi(service, apiKey, prompt, model, params, customBaseUrl, callback);
        return;
    }

    // Build messages array
    QJsonArray messages = buildOpenAiMessages(prompt, params.systemPrompt);

    // Build request body
    QJsonObject body;
    body[QStringLiteral("model")] = model;
    body[QStringLiteral("messages")] = messages;

    // Optional parameters - only include if set
    if (params.maxTokens.has_value())
        body[QStringLiteral("max_tokens")] = *params.maxTokens;
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

    // Stop sequences
    if (params.stopSequences.has_value() && !params.stopSequences->isEmpty()) {
        QJsonArray stopArr;
        for (const auto &s : *params.stopSequences)
            stopArr.append(s);
        body[QStringLiteral("stop")] = stopArr;
    }

    // Seed field name varies by provider ("seed" or "random_seed")
    if (params.seed.has_value()) {
        body[service.seedFieldName] = *params.seed;
    }

    // Response format (JSON mode)
    if (params.responseFormatJson) {
        QJsonObject fmt;
        fmt[QStringLiteral("type")] = QStringLiteral("json_object");
        body[QStringLiteral("response_format")] = fmt;
    }

    // Provider-specific features
    if (service.supportsCitations && params.returnCitations)
        body[QStringLiteral("return_citations")] = true;
    if (service.supportsSearchRecency && params.searchRecency.has_value())
        body[QStringLiteral("search_recency_filter")] = *params.searchRecency;
    if (params.searchEnabled)
        body[QStringLiteral("search")] = true;

    // URL
    QString url = ApiClient::chatUrl(service, customBaseUrl);
    QString svcId = service.id;

    ApiClient::instance().request(url, body, service, apiKey,
        [svcId, callback](const QJsonObject &json, int statusCode, const QString &headers) {
            AnalysisResponse resp(svcId);
            resp.httpStatusCode = statusCode;
            resp.httpHeaders = headers;

            // Check for API error in response body
            if (json.contains(QStringLiteral("error"))) {
                QJsonObject errObj = json[QStringLiteral("error")].toObject();
                QString errMsg = errObj[QStringLiteral("message")].toString();
                if (errMsg.isEmpty())
                    errMsg = QStringLiteral("API error: %1").arg(statusCode);
                // Include error body text if present
                if (errObj.contains(QStringLiteral("body")))
                    errMsg += QStringLiteral(" - ") + errObj[QStringLiteral("body")].toString();
                resp.error = errMsg;
                if (callback) callback(resp);
                return;
            }

            // Parse content from choices[0].message.content or reasoning_content
            QString content;
            QJsonArray choices = json[QStringLiteral("choices")].toArray();
            if (!choices.isEmpty()) {
                QJsonObject firstChoice = choices[0].toObject();
                QJsonObject message = firstChoice[QStringLiteral("message")].toObject();
                content = message[QStringLiteral("content")].toString();
                if (content.isEmpty())
                    content = message[QStringLiteral("reasoning_content")].toString();
                // Fallback: try other choices
                if (content.isEmpty()) {
                    for (const auto &choiceVal : choices) {
                        QJsonObject msg = choiceVal.toObject()[QStringLiteral("message")].toObject();
                        content = msg[QStringLiteral("content")].toString();
                        if (content.isEmpty())
                            content = msg[QStringLiteral("reasoning_content")].toString();
                        if (!content.isEmpty()) break;
                    }
                }
            }

            // Extract token usage
            if (json.contains(QStringLiteral("usage"))) {
                QJsonObject usage = json[QStringLiteral("usage")].toObject();
                TokenUsage tu;
                tu.inputTokens = usage[QStringLiteral("prompt_tokens")].toInt(
                    usage[QStringLiteral("input_tokens")].toInt(0));
                tu.outputTokens = usage[QStringLiteral("completion_tokens")].toInt(
                    usage[QStringLiteral("output_tokens")].toInt(0));
                tu.apiCost = AnalysisRepository::extractOpenAiCost(usage,
                    AppService::findById(svcId));
                resp.tokenUsage = tu;
                resp.rawUsageJson = AnalysisRepository::formatUsageJson(usage);
            }

            // Citations
            if (json.contains(QStringLiteral("citations"))) {
                QStringList cites;
                for (const auto &v : json[QStringLiteral("citations")].toArray())
                    cites.append(v.toString());
                if (!cites.isEmpty())
                    resp.citations = cites;
            }

            // Search results
            if (json.contains(QStringLiteral("search_results"))) {
                QList<SearchResult> results;
                for (const auto &v : json[QStringLiteral("search_results")].toArray()) {
                    results.append(SearchResult::fromJson(v.toObject()));
                }
                if (!results.isEmpty())
                    resp.searchResults = results;
            }

            // Related questions
            if (json.contains(QStringLiteral("related_questions"))) {
                QStringList questions;
                for (const auto &v : json[QStringLiteral("related_questions")].toArray())
                    questions.append(v.toString());
                if (!questions.isEmpty())
                    resp.relatedQuestions = questions;
            }

            if (!content.isEmpty()) {
                resp.analysis = content;
            } else {
                resp.error = QStringLiteral("No response content (choices: %1)")
                    .arg(choices.size());
            }

            if (callback) callback(resp);
        });
}

// ---------------------------------------------------------------------------
// OpenAI Responses API (for gpt-5.x, o3, o4 and newer)
// ---------------------------------------------------------------------------

void AnalysisProviders::analyzeResponsesApi(const AppService &service, const QString &apiKey,
                                              const QString &prompt, const QString &model,
                                              const AgentParameters &params,
                                              const QString &customBaseUrl,
                                              std::function<void(AnalysisResponse)> callback)
{
    QJsonObject body;
    body[QStringLiteral("model")] = model;
    body[QStringLiteral("input")] = prompt;

    if (params.systemPrompt.has_value() && !params.systemPrompt->isEmpty())
        body[QStringLiteral("instructions")] = *params.systemPrompt;

    // Build URL: {baseUrl}v1/responses
    const QString &base = customBaseUrl.isEmpty() ? service.baseUrl : customBaseUrl;
    QString normalizedBase = base.endsWith(QLatin1Char('/')) ? base : (base + QLatin1Char('/'));
    QString url = normalizedBase + QStringLiteral("v1/responses");
    QString svcId = service.id;

    ApiClient::instance().request(url, body, service, apiKey,
        [svcId, callback](const QJsonObject &json, int statusCode, const QString &headers) {
            AnalysisResponse resp(svcId);
            resp.httpStatusCode = statusCode;
            resp.httpHeaders = headers;

            // Check for error
            if (json.contains(QStringLiteral("error"))) {
                QJsonObject errObj = json[QStringLiteral("error")].toObject();
                QString errMsg = errObj[QStringLiteral("message")].toString();
                if (errMsg.isEmpty())
                    errMsg = QStringLiteral("API error: %1").arg(statusCode);
                resp.error = errMsg;
                if (callback) callback(resp);
                return;
            }

            // Parse: output[0].content[0] where type == "output_text"
            QString content;
            QJsonArray outputs = json[QStringLiteral("output")].toArray();
            for (const auto &outputVal : outputs) {
                QJsonObject outputMsg = outputVal.toObject();
                QJsonArray contentArr = outputMsg[QStringLiteral("content")].toArray();
                for (const auto &contentVal : contentArr) {
                    QJsonObject contentObj = contentVal.toObject();
                    QString type = contentObj[QStringLiteral("type")].toString();
                    if (type == QStringLiteral("output_text")) {
                        content = contentObj[QStringLiteral("text")].toString();
                        break;
                    }
                }
                if (!content.isEmpty()) break;
            }

            // Fallback: try type == "text" or any text field
            if (content.isEmpty()) {
                for (const auto &outputVal : outputs) {
                    QJsonObject outputMsg = outputVal.toObject();
                    QJsonArray contentArr = outputMsg[QStringLiteral("content")].toArray();
                    for (const auto &contentVal : contentArr) {
                        QJsonObject contentObj = contentVal.toObject();
                        QString type = contentObj[QStringLiteral("type")].toString();
                        if (type == QStringLiteral("text") || contentObj.contains(QStringLiteral("text"))) {
                            content = contentObj[QStringLiteral("text")].toString();
                            if (!content.isEmpty()) break;
                        }
                    }
                    if (!content.isEmpty()) break;
                }
            }

            // Fallback: try output messages with type "message"
            if (content.isEmpty()) {
                for (const auto &outputVal : outputs) {
                    QJsonObject outputMsg = outputVal.toObject();
                    if (outputMsg[QStringLiteral("type")].toString() == QStringLiteral("message")) {
                        QJsonArray contentArr = outputMsg[QStringLiteral("content")].toArray();
                        for (const auto &contentVal : contentArr) {
                            content = contentVal.toObject()[QStringLiteral("text")].toString();
                            if (!content.isEmpty()) break;
                        }
                    }
                    if (!content.isEmpty()) break;
                }
            }

            // Extract token usage
            if (json.contains(QStringLiteral("usage"))) {
                QJsonObject usage = json[QStringLiteral("usage")].toObject();
                TokenUsage tu;
                tu.inputTokens = usage[QStringLiteral("input_tokens")].toInt(
                    usage[QStringLiteral("prompt_tokens")].toInt(0));
                tu.outputTokens = usage[QStringLiteral("output_tokens")].toInt(
                    usage[QStringLiteral("completion_tokens")].toInt(0));
                tu.apiCost = AnalysisRepository::extractOpenAiCost(usage,
                    AppService::findById(svcId));
                resp.tokenUsage = tu;
                resp.rawUsageJson = AnalysisRepository::formatUsageJson(usage);
            }

            if (!content.isEmpty()) {
                resp.analysis = content;
            } else {
                resp.error = QStringLiteral("No response content (output: %1)")
                    .arg(outputs.size());
            }

            if (callback) callback(resp);
        });
}

// ---------------------------------------------------------------------------
// Anthropic Claude (Messages API)
// ---------------------------------------------------------------------------

void AnalysisProviders::analyzeClaude(const AppService &service, const QString &apiKey,
                                        const QString &prompt, const QString &model,
                                        const AgentParameters &params,
                                        std::function<void(AnalysisResponse)> callback)
{
    QJsonObject body;
    body[QStringLiteral("model")] = model;
    body[QStringLiteral("max_tokens")] = params.maxTokens.value_or(4096);

    // Messages: single user message
    QJsonArray messages;
    QJsonObject userMsg;
    userMsg[QStringLiteral("role")] = QStringLiteral("user");
    userMsg[QStringLiteral("content")] = prompt;
    messages.append(userMsg);
    body[QStringLiteral("messages")] = messages;

    // System prompt is a top-level field for Claude (not in messages)
    if (params.systemPrompt.has_value() && !params.systemPrompt->isEmpty())
        body[QStringLiteral("system")] = *params.systemPrompt;

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
    if (params.seed.has_value())
        body[QStringLiteral("seed")] = *params.seed;

    // Stop sequences
    if (params.stopSequences.has_value() && !params.stopSequences->isEmpty()) {
        QJsonArray stopArr;
        for (const auto &s : *params.stopSequences)
            stopArr.append(s);
        body[QStringLiteral("stop_sequences")] = stopArr;
    }

    // Web search
    if (params.searchEnabled)
        body[QStringLiteral("search")] = true;

    // URL: {baseUrl}v1/messages
    QString base = service.baseUrl;
    if (!base.endsWith(QLatin1Char('/')))
        base += QLatin1Char('/');
    QString url = base + QStringLiteral("v1/messages");
    QString svcId = service.id;

    ApiClient::instance().request(url, body, service, apiKey,
        [svcId, callback](const QJsonObject &json, int statusCode, const QString &headers) {
            AnalysisResponse resp(svcId);
            resp.httpStatusCode = statusCode;
            resp.httpHeaders = headers;

            // Check for error
            if (json.contains(QStringLiteral("error"))) {
                QJsonObject errObj = json[QStringLiteral("error")].toObject();
                QString errMsg = errObj[QStringLiteral("message")].toString();
                if (errMsg.isEmpty())
                    errMsg = QStringLiteral("API error: %1").arg(statusCode);
                resp.error = errMsg;
                if (callback) callback(resp);
                return;
            }

            // Parse: content[0] where type == "text"
            QString content;
            QJsonArray contentBlocks = json[QStringLiteral("content")].toArray();
            for (const auto &blockVal : contentBlocks) {
                QJsonObject block = blockVal.toObject();
                if (block[QStringLiteral("type")].toString() == QStringLiteral("text")) {
                    content = block[QStringLiteral("text")].toString();
                    break;
                }
            }
            // Fallback: any block with text
            if (content.isEmpty()) {
                for (const auto &blockVal : contentBlocks) {
                    QJsonObject block = blockVal.toObject();
                    if (block.contains(QStringLiteral("text"))) {
                        content = block[QStringLiteral("text")].toString();
                        if (!content.isEmpty()) break;
                    }
                }
            }

            // Extract token usage
            if (json.contains(QStringLiteral("usage"))) {
                QJsonObject usage = json[QStringLiteral("usage")].toObject();
                TokenUsage tu;
                tu.inputTokens = usage[QStringLiteral("input_tokens")].toInt(0);
                tu.outputTokens = usage[QStringLiteral("output_tokens")].toInt(0);
                tu.apiCost = AnalysisRepository::extractClaudeCost(usage);
                resp.tokenUsage = tu;
                resp.rawUsageJson = AnalysisRepository::formatUsageJson(usage);
            }

            if (!content.isEmpty()) {
                resp.analysis = content;
            } else {
                resp.error = QStringLiteral("No response content (blocks: %1)")
                    .arg(contentBlocks.size());
            }

            if (callback) callback(resp);
        });
}

// ---------------------------------------------------------------------------
// Google Gemini (GenerativeAI API)
// ---------------------------------------------------------------------------

void AnalysisProviders::analyzeGemini(const AppService &service, const QString &apiKey,
                                        const QString &prompt, const QString &model,
                                        const AgentParameters &params,
                                        std::function<void(AnalysisResponse)> callback)
{
    QJsonObject body;

    // Contents: single user message with text part
    QJsonArray contents;
    QJsonObject userContent;
    userContent[QStringLiteral("role")] = QStringLiteral("user");
    QJsonArray parts;
    QJsonObject textPart;
    textPart[QStringLiteral("text")] = prompt;
    parts.append(textPart);
    userContent[QStringLiteral("parts")] = parts;
    contents.append(userContent);
    body[QStringLiteral("contents")] = contents;

    // Generation config with all parameters
    QJsonObject genConfig;
    bool hasGenConfig = false;

    if (params.temperature.has_value()) {
        genConfig[QStringLiteral("temperature")] = static_cast<double>(*params.temperature);
        hasGenConfig = true;
    }
    if (params.topP.has_value()) {
        genConfig[QStringLiteral("topP")] = static_cast<double>(*params.topP);
        hasGenConfig = true;
    }
    if (params.topK.has_value()) {
        genConfig[QStringLiteral("topK")] = *params.topK;
        hasGenConfig = true;
    }
    if (params.maxTokens.has_value()) {
        genConfig[QStringLiteral("maxOutputTokens")] = *params.maxTokens;
        hasGenConfig = true;
    }
    if (params.stopSequences.has_value() && !params.stopSequences->isEmpty()) {
        QJsonArray stopArr;
        for (const auto &s : *params.stopSequences)
            stopArr.append(s);
        genConfig[QStringLiteral("stopSequences")] = stopArr;
        hasGenConfig = true;
    }
    if (params.frequencyPenalty.has_value()) {
        genConfig[QStringLiteral("frequencyPenalty")] = static_cast<double>(*params.frequencyPenalty);
        hasGenConfig = true;
    }
    if (params.presencePenalty.has_value()) {
        genConfig[QStringLiteral("presencePenalty")] = static_cast<double>(*params.presencePenalty);
        hasGenConfig = true;
    }
    if (params.seed.has_value()) {
        genConfig[QStringLiteral("seed")] = *params.seed;
        hasGenConfig = true;
    }

    if (hasGenConfig)
        body[QStringLiteral("generationConfig")] = genConfig;

    // System instruction (separate top-level field for Gemini)
    if (params.systemPrompt.has_value() && !params.systemPrompt->isEmpty()) {
        QJsonObject sysInstruction;
        QJsonArray sysParts;
        QJsonObject sysTextPart;
        sysTextPart[QStringLiteral("text")] = *params.systemPrompt;
        sysParts.append(sysTextPart);
        sysInstruction[QStringLiteral("parts")] = sysParts;
        body[QStringLiteral("systemInstruction")] = sysInstruction;
    }

    // Search grounding
    if (params.searchEnabled) {
        genConfig[QStringLiteral("search")] = true;
        body[QStringLiteral("generationConfig")] = genConfig;
    }

    // URL: Gemini uses ?key= for auth, model name in the path
    QString url = ApiClient::geminiGenerateUrl(service.baseUrl, model, apiKey, /*stream=*/false);
    QString svcId = service.id;

    // Gemini uses key in URL, not auth headers - create a temporary service with Google format
    // (ApiClient handles this: for Google format, no auth headers are set)
    ApiClient::instance().request(url, body, service, apiKey,
        [svcId, callback](const QJsonObject &json, int statusCode, const QString &headers) {
            AnalysisResponse resp(svcId);
            resp.httpStatusCode = statusCode;
            resp.httpHeaders = headers;

            // Check for error
            if (json.contains(QStringLiteral("error"))) {
                QJsonObject errObj = json[QStringLiteral("error")].toObject();
                QString errMsg = errObj[QStringLiteral("message")].toString();
                if (errMsg.isEmpty())
                    errMsg = QStringLiteral("API error: %1").arg(statusCode);
                resp.error = errMsg;
                if (callback) callback(resp);
                return;
            }

            // Parse: candidates[0].content.parts[0].text
            QString content;
            QJsonArray candidates = json[QStringLiteral("candidates")].toArray();
            if (!candidates.isEmpty()) {
                QJsonObject firstCandidate = candidates[0].toObject();
                QJsonObject candidateContent = firstCandidate[QStringLiteral("content")].toObject();
                QJsonArray candidateParts = candidateContent[QStringLiteral("parts")].toArray();
                if (!candidateParts.isEmpty()) {
                    content = candidateParts[0].toObject()[QStringLiteral("text")].toString();
                }
                // Fallback: any part with text from first candidate
                if (content.isEmpty()) {
                    for (const auto &partVal : candidateParts) {
                        content = partVal.toObject()[QStringLiteral("text")].toString();
                        if (!content.isEmpty()) break;
                    }
                }
                // Fallback: try any candidate with non-null content
                if (content.isEmpty()) {
                    for (const auto &candVal : candidates) {
                        QJsonArray cParts = candVal.toObject()[QStringLiteral("content")]
                            .toObject()[QStringLiteral("parts")].toArray();
                        for (const auto &partVal : cParts) {
                            content = partVal.toObject()[QStringLiteral("text")].toString();
                            if (!content.isEmpty()) break;
                        }
                        if (!content.isEmpty()) break;
                    }
                }
            }

            // Extract token usage from usageMetadata
            if (json.contains(QStringLiteral("usageMetadata"))) {
                QJsonObject usage = json[QStringLiteral("usageMetadata")].toObject();
                TokenUsage tu;
                tu.inputTokens = usage[QStringLiteral("promptTokenCount")].toInt(0);
                tu.outputTokens = usage[QStringLiteral("candidatesTokenCount")].toInt(0);
                tu.apiCost = AnalysisRepository::extractGeminiCost(usage);
                resp.tokenUsage = tu;
                resp.rawUsageJson = AnalysisRepository::formatUsageJson(usage);
            }

            if (!content.isEmpty()) {
                resp.analysis = content;
            } else {
                resp.error = QStringLiteral("No response content (candidates: %1)")
                    .arg(candidates.size());
            }

            if (callback) callback(resp);
        });
}

// ---------------------------------------------------------------------------
// Helper: build OpenAI messages array (system + user)
// ---------------------------------------------------------------------------

QJsonArray AnalysisProviders::buildOpenAiMessages(const QString &prompt,
                                                    const std::optional<QString> &systemPrompt)
{
    QJsonArray messages;

    if (systemPrompt.has_value() && !systemPrompt->isEmpty()) {
        QJsonObject sysMsg;
        sysMsg[QStringLiteral("role")] = QStringLiteral("system");
        sysMsg[QStringLiteral("content")] = *systemPrompt;
        messages.append(sysMsg);
    }

    QJsonObject userMsg;
    userMsg[QStringLiteral("role")] = QStringLiteral("user");
    userMsg[QStringLiteral("content")] = prompt;
    messages.append(userMsg);

    return messages;
}
