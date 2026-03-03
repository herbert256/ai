// DataModels.h - All data model structs for the AI app (Linux/Qt6 port)
// Header-only, no .cpp needed

#pragma once

#include <QString>
#include <QList>
#include <QStringList>
#include <QUuid>
#include <QDateTime>
#include <QJsonObject>
#include <QJsonArray>
#include <QJsonDocument>
#include <QMetaType>
#include <optional>

// ---------------------------------------------------------------------------
// AgentParameters - configurable AI parameters
// ---------------------------------------------------------------------------

struct AgentParameters {
    Q_GADGET
public:
    std::optional<float> temperature;
    std::optional<int> maxTokens;
    std::optional<float> topP;
    std::optional<int> topK;
    std::optional<float> frequencyPenalty;
    std::optional<float> presencePenalty;
    std::optional<QString> systemPrompt;
    std::optional<QStringList> stopSequences;
    std::optional<int> seed;
    bool responseFormatJson = false;
    bool searchEnabled = false;
    bool returnCitations = true;
    std::optional<QString> searchRecency; // "day", "week", "month", "year"

    QJsonObject toJson() const {
        QJsonObject obj;
        if (temperature.has_value())
            obj["temperature"] = static_cast<double>(*temperature);
        if (maxTokens.has_value())
            obj["maxTokens"] = *maxTokens;
        if (topP.has_value())
            obj["topP"] = static_cast<double>(*topP);
        if (topK.has_value())
            obj["topK"] = *topK;
        if (frequencyPenalty.has_value())
            obj["frequencyPenalty"] = static_cast<double>(*frequencyPenalty);
        if (presencePenalty.has_value())
            obj["presencePenalty"] = static_cast<double>(*presencePenalty);
        if (systemPrompt.has_value())
            obj["systemPrompt"] = *systemPrompt;
        if (stopSequences.has_value()) {
            QJsonArray arr;
            for (const auto &s : *stopSequences)
                arr.append(s);
            obj["stopSequences"] = arr;
        }
        if (seed.has_value())
            obj["seed"] = *seed;
        obj["responseFormatJson"] = responseFormatJson;
        obj["searchEnabled"] = searchEnabled;
        obj["returnCitations"] = returnCitations;
        if (searchRecency.has_value())
            obj["searchRecency"] = *searchRecency;
        return obj;
    }

    static AgentParameters fromJson(const QJsonObject &obj) {
        AgentParameters p;
        if (obj.contains("temperature"))
            p.temperature = static_cast<float>(obj["temperature"].toDouble());
        if (obj.contains("maxTokens"))
            p.maxTokens = obj["maxTokens"].toInt();
        if (obj.contains("topP"))
            p.topP = static_cast<float>(obj["topP"].toDouble());
        if (obj.contains("topK"))
            p.topK = obj["topK"].toInt();
        if (obj.contains("frequencyPenalty"))
            p.frequencyPenalty = static_cast<float>(obj["frequencyPenalty"].toDouble());
        if (obj.contains("presencePenalty"))
            p.presencePenalty = static_cast<float>(obj["presencePenalty"].toDouble());
        if (obj.contains("systemPrompt"))
            p.systemPrompt = obj["systemPrompt"].toString();
        if (obj.contains("stopSequences")) {
            QStringList list;
            for (const auto &v : obj["stopSequences"].toArray())
                list.append(v.toString());
            p.stopSequences = list;
        }
        if (obj.contains("seed"))
            p.seed = obj["seed"].toInt();
        p.responseFormatJson = obj["responseFormatJson"].toBool(false);
        p.searchEnabled = obj["searchEnabled"].toBool(false);
        p.returnCitations = obj["returnCitations"].toBool(true);
        if (obj.contains("searchRecency"))
            p.searchRecency = obj["searchRecency"].toString();
        return p;
    }

    bool operator==(const AgentParameters &other) const = default;
};

// ---------------------------------------------------------------------------
// ChatMessage - a single message in a chat conversation
// ---------------------------------------------------------------------------

struct ChatMessage {
    Q_GADGET
public:
    QString id;        // UUID string
    QString role;      // "system", "user", "assistant"
    QString content;
    QDateTime timestamp;

    ChatMessage() = default;

    ChatMessage(const QString &role, const QString &content,
                const QDateTime &timestamp = QDateTime::currentDateTimeUtc())
        : id(QUuid::createUuid().toString(QUuid::WithoutBraces))
        , role(role)
        , content(content)
        , timestamp(timestamp)
    {}

    QJsonObject toJson() const {
        QJsonObject obj;
        obj["id"] = id;
        obj["role"] = role;
        obj["content"] = content;
        obj["timestamp"] = timestamp.toMSecsSinceEpoch();
        return obj;
    }

    static ChatMessage fromJson(const QJsonObject &obj) {
        ChatMessage msg;
        msg.id = obj["id"].toString();
        msg.role = obj["role"].toString();
        msg.content = obj["content"].toString();
        msg.timestamp = QDateTime::fromMSecsSinceEpoch(
            static_cast<qint64>(obj["timestamp"].toDouble()));
        return msg;
    }

    bool operator==(const ChatMessage &other) const = default;
};

// ---------------------------------------------------------------------------
// ChatParameters - subset of AgentParameters for chat
// ---------------------------------------------------------------------------

struct ChatParameters {
    Q_GADGET
public:
    QString systemPrompt;
    std::optional<float> temperature;
    std::optional<int> maxTokens;
    std::optional<float> topP;
    std::optional<int> topK;
    std::optional<float> frequencyPenalty;
    std::optional<float> presencePenalty;
    bool searchEnabled = false;
    bool returnCitations = true;
    std::optional<QString> searchRecency;

    QJsonObject toJson() const {
        QJsonObject obj;
        obj["systemPrompt"] = systemPrompt;
        if (temperature.has_value())
            obj["temperature"] = static_cast<double>(*temperature);
        if (maxTokens.has_value())
            obj["maxTokens"] = *maxTokens;
        if (topP.has_value())
            obj["topP"] = static_cast<double>(*topP);
        if (topK.has_value())
            obj["topK"] = *topK;
        if (frequencyPenalty.has_value())
            obj["frequencyPenalty"] = static_cast<double>(*frequencyPenalty);
        if (presencePenalty.has_value())
            obj["presencePenalty"] = static_cast<double>(*presencePenalty);
        obj["searchEnabled"] = searchEnabled;
        obj["returnCitations"] = returnCitations;
        if (searchRecency.has_value())
            obj["searchRecency"] = *searchRecency;
        return obj;
    }

    static ChatParameters fromJson(const QJsonObject &obj) {
        ChatParameters p;
        p.systemPrompt = obj["systemPrompt"].toString();
        if (obj.contains("temperature"))
            p.temperature = static_cast<float>(obj["temperature"].toDouble());
        if (obj.contains("maxTokens"))
            p.maxTokens = obj["maxTokens"].toInt();
        if (obj.contains("topP"))
            p.topP = static_cast<float>(obj["topP"].toDouble());
        if (obj.contains("topK"))
            p.topK = obj["topK"].toInt();
        if (obj.contains("frequencyPenalty"))
            p.frequencyPenalty = static_cast<float>(obj["frequencyPenalty"].toDouble());
        if (obj.contains("presencePenalty"))
            p.presencePenalty = static_cast<float>(obj["presencePenalty"].toDouble());
        p.searchEnabled = obj["searchEnabled"].toBool(false);
        p.returnCitations = obj["returnCitations"].toBool(true);
        if (obj.contains("searchRecency"))
            p.searchRecency = obj["searchRecency"].toString();
        return p;
    }

    bool operator==(const ChatParameters &other) const = default;
};

// ---------------------------------------------------------------------------
// ChatSession - a saved chat session with all messages
// ---------------------------------------------------------------------------

struct ChatSession {
    Q_GADGET
public:
    QString id;          // UUID string
    QString providerId;
    QString model;
    QList<ChatMessage> messages;
    ChatParameters parameters;
    QDateTime createdAt;
    QDateTime updatedAt;

    ChatSession() = default;

    ChatSession(const QString &providerId, const QString &model,
                const QList<ChatMessage> &messages = {},
                const ChatParameters &parameters = {},
                const QDateTime &createdAt = QDateTime::currentDateTimeUtc(),
                const QDateTime &updatedAt = QDateTime::currentDateTimeUtc())
        : id(QUuid::createUuid().toString(QUuid::WithoutBraces))
        , providerId(providerId)
        , model(model)
        , messages(messages)
        , parameters(parameters)
        , createdAt(createdAt)
        , updatedAt(updatedAt)
    {}

    /// Preview text from first user message (first 50 characters).
    QString preview() const {
        for (const auto &msg : messages) {
            if (msg.role == QStringLiteral("user"))
                return msg.content.left(50);
        }
        return QStringLiteral("Empty chat");
    }

    QJsonObject toJson() const {
        QJsonObject obj;
        obj["id"] = id;
        obj["providerId"] = providerId;
        obj["model"] = model;
        QJsonArray msgsArr;
        for (const auto &msg : messages)
            msgsArr.append(msg.toJson());
        obj["messages"] = msgsArr;
        obj["parameters"] = parameters.toJson();
        obj["createdAt"] = createdAt.toMSecsSinceEpoch();
        obj["updatedAt"] = updatedAt.toMSecsSinceEpoch();
        return obj;
    }

    static ChatSession fromJson(const QJsonObject &obj) {
        ChatSession s;
        s.id = obj["id"].toString();
        s.providerId = obj["providerId"].toString();
        s.model = obj["model"].toString();
        for (const auto &v : obj["messages"].toArray())
            s.messages.append(ChatMessage::fromJson(v.toObject()));
        s.parameters = ChatParameters::fromJson(obj["parameters"].toObject());
        s.createdAt = QDateTime::fromMSecsSinceEpoch(
            static_cast<qint64>(obj["createdAt"].toDouble()));
        s.updatedAt = QDateTime::fromMSecsSinceEpoch(
            static_cast<qint64>(obj["updatedAt"].toDouble()));
        return s;
    }
};

// ---------------------------------------------------------------------------
// DualChatConfig - configuration for dual-chat (two AI models conversing)
// ---------------------------------------------------------------------------

struct DualChatConfig {
    Q_GADGET
public:
    QString model1ProviderId;
    QString model1Name;
    QString model1SystemPrompt;
    ChatParameters model1Params;
    QString model2ProviderId;
    QString model2Name;
    QString model2SystemPrompt;
    ChatParameters model2Params;
    QString subject;
    int interactionCount = 10;
    QString firstPrompt = QStringLiteral("Let's talk about %subject%");
    QString secondPrompt = QStringLiteral("What do you think about: %answer%");

    QJsonObject toJson() const {
        QJsonObject obj;
        obj["model1ProviderId"] = model1ProviderId;
        obj["model1Name"] = model1Name;
        obj["model1SystemPrompt"] = model1SystemPrompt;
        obj["model1Params"] = model1Params.toJson();
        obj["model2ProviderId"] = model2ProviderId;
        obj["model2Name"] = model2Name;
        obj["model2SystemPrompt"] = model2SystemPrompt;
        obj["model2Params"] = model2Params.toJson();
        obj["subject"] = subject;
        obj["interactionCount"] = interactionCount;
        obj["firstPrompt"] = firstPrompt;
        obj["secondPrompt"] = secondPrompt;
        return obj;
    }

    static DualChatConfig fromJson(const QJsonObject &obj) {
        DualChatConfig c;
        c.model1ProviderId = obj["model1ProviderId"].toString();
        c.model1Name = obj["model1Name"].toString();
        c.model1SystemPrompt = obj["model1SystemPrompt"].toString();
        c.model1Params = ChatParameters::fromJson(obj["model1Params"].toObject());
        c.model2ProviderId = obj["model2ProviderId"].toString();
        c.model2Name = obj["model2Name"].toString();
        c.model2SystemPrompt = obj["model2SystemPrompt"].toString();
        c.model2Params = ChatParameters::fromJson(obj["model2Params"].toObject());
        c.subject = obj["subject"].toString();
        c.interactionCount = obj["interactionCount"].toInt(10);
        c.firstPrompt = obj["firstPrompt"].toString(
            QStringLiteral("Let's talk about %subject%"));
        c.secondPrompt = obj["secondPrompt"].toString(
            QStringLiteral("What do you think about: %answer%"));
        return c;
    }
};

// ---------------------------------------------------------------------------
// TokenUsage - token counts and optional cost from an API call
// ---------------------------------------------------------------------------

struct TokenUsage {
    Q_GADGET
public:
    int inputTokens = 0;
    int outputTokens = 0;
    std::optional<double> apiCost;

    int totalTokens() const { return inputTokens + outputTokens; }

    QJsonObject toJson() const {
        QJsonObject obj;
        obj["inputTokens"] = inputTokens;
        obj["outputTokens"] = outputTokens;
        if (apiCost.has_value())
            obj["apiCost"] = *apiCost;
        return obj;
    }

    static TokenUsage fromJson(const QJsonObject &obj) {
        TokenUsage t;
        t.inputTokens = obj["inputTokens"].toInt(0);
        t.outputTokens = obj["outputTokens"].toInt(0);
        if (obj.contains("apiCost"))
            t.apiCost = obj["apiCost"].toDouble();
        return t;
    }

    bool operator==(const TokenUsage &other) const = default;
};

// ---------------------------------------------------------------------------
// ReportStatus - lifecycle state of a report agent
// ---------------------------------------------------------------------------

enum class ReportStatus {
    Pending,
    Running,
    Success,
    Error,
    Stopped
};

inline QString reportStatusToString(ReportStatus s) {
    switch (s) {
    case ReportStatus::Pending: return QStringLiteral("PENDING");
    case ReportStatus::Running: return QStringLiteral("RUNNING");
    case ReportStatus::Success: return QStringLiteral("SUCCESS");
    case ReportStatus::Error:   return QStringLiteral("ERROR");
    case ReportStatus::Stopped: return QStringLiteral("STOPPED");
    }
    return QStringLiteral("PENDING");
}

inline ReportStatus reportStatusFromString(const QString &s) {
    if (s == QStringLiteral("RUNNING")) return ReportStatus::Running;
    if (s == QStringLiteral("SUCCESS")) return ReportStatus::Success;
    if (s == QStringLiteral("ERROR"))   return ReportStatus::Error;
    if (s == QStringLiteral("STOPPED")) return ReportStatus::Stopped;
    return ReportStatus::Pending;
}

// ---------------------------------------------------------------------------
// ReportAgent - tracks state of a single agent within a report run
// ---------------------------------------------------------------------------

struct ReportAgent {
    Q_GADGET
public:
    QString id;
    QString agentId;
    QString agentName;
    QString providerId;
    QString model;
    ReportStatus status = ReportStatus::Pending;

    ReportAgent() = default;

    ReportAgent(const QString &agentId, const QString &agentName,
                const QString &providerId, const QString &model,
                ReportStatus status = ReportStatus::Pending)
        : id(agentId)
        , agentId(agentId)
        , agentName(agentName)
        , providerId(providerId)
        , model(model)
        , status(status)
    {}
};

// ---------------------------------------------------------------------------
// SearchResult - a single web search result from an API response
// ---------------------------------------------------------------------------

struct SearchResult {
    std::optional<QString> name;
    std::optional<QString> url;
    std::optional<QString> snippet;

    QJsonObject toJson() const {
        QJsonObject obj;
        if (name.has_value()) obj["name"] = *name;
        if (url.has_value()) obj["url"] = *url;
        if (snippet.has_value()) obj["snippet"] = *snippet;
        return obj;
    }

    static SearchResult fromJson(const QJsonObject &obj) {
        SearchResult r;
        if (obj.contains("name")) r.name = obj["name"].toString();
        if (obj.contains("url")) r.url = obj["url"].toString();
        if (obj.contains("snippet")) r.snippet = obj["snippet"].toString();
        return r;
    }
};

// ---------------------------------------------------------------------------
// AnalysisResponse - result from a single AI analysis
// ---------------------------------------------------------------------------

struct AnalysisResponse {
    QString id;                                // UUID
    QString serviceId;                         // provider ID
    std::optional<QString> analysis;
    std::optional<QString> error;
    std::optional<TokenUsage> tokenUsage;
    std::optional<QString> agentName;
    std::optional<QString> promptUsed;
    std::optional<QStringList> citations;
    std::optional<QList<SearchResult>> searchResults;
    std::optional<QStringList> relatedQuestions;
    std::optional<QString> rawUsageJson;
    std::optional<QString> httpHeaders;
    std::optional<int> httpStatusCode;

    AnalysisResponse()
        : id(QUuid::createUuid().toString(QUuid::WithoutBraces))
    {}

    explicit AnalysisResponse(const QString &serviceId)
        : id(QUuid::createUuid().toString(QUuid::WithoutBraces))
        , serviceId(serviceId)
    {}

    bool isSuccess() const {
        return analysis.has_value() && !error.has_value();
    }
};

// ---------------------------------------------------------------------------
// StoredAnalysisResult - persisted form of a single analysis result
// ---------------------------------------------------------------------------

struct StoredAnalysisResult {
    Q_GADGET
public:
    QString id;
    QString providerId;
    QString model;
    std::optional<QString> agentName;
    std::optional<QString> analysis;
    std::optional<QString> error;
    int inputTokens = 0;
    int outputTokens = 0;
    std::optional<double> apiCost;
    std::optional<QStringList> citations;
    std::optional<QString> rawUsageJson;

    QJsonObject toJson() const {
        QJsonObject obj;
        obj["id"] = id;
        obj["providerId"] = providerId;
        obj["model"] = model;
        if (agentName.has_value())
            obj["agentName"] = *agentName;
        if (analysis.has_value())
            obj["analysis"] = *analysis;
        if (error.has_value())
            obj["error"] = *error;
        obj["inputTokens"] = inputTokens;
        obj["outputTokens"] = outputTokens;
        if (apiCost.has_value())
            obj["apiCost"] = *apiCost;
        if (citations.has_value()) {
            QJsonArray arr;
            for (const auto &c : *citations)
                arr.append(c);
            obj["citations"] = arr;
        }
        if (rawUsageJson.has_value())
            obj["rawUsageJson"] = *rawUsageJson;
        return obj;
    }

    static StoredAnalysisResult fromJson(const QJsonObject &obj) {
        StoredAnalysisResult r;
        r.id = obj["id"].toString();
        r.providerId = obj["providerId"].toString();
        r.model = obj["model"].toString();
        if (obj.contains("agentName"))
            r.agentName = obj["agentName"].toString();
        if (obj.contains("analysis"))
            r.analysis = obj["analysis"].toString();
        if (obj.contains("error"))
            r.error = obj["error"].toString();
        r.inputTokens = obj["inputTokens"].toInt(0);
        r.outputTokens = obj["outputTokens"].toInt(0);
        if (obj.contains("apiCost"))
            r.apiCost = obj["apiCost"].toDouble();
        if (obj.contains("citations")) {
            QStringList list;
            for (const auto &v : obj["citations"].toArray())
                list.append(v.toString());
            r.citations = list;
        }
        if (obj.contains("rawUsageJson"))
            r.rawUsageJson = obj["rawUsageJson"].toString();
        return r;
    }
};

// ---------------------------------------------------------------------------
// StoredReport - persisted report with all analysis results
// ---------------------------------------------------------------------------

struct StoredReport {
    Q_GADGET
public:
    QString id;
    QString title;
    QString prompt;
    QDateTime timestamp;
    QList<StoredAnalysisResult> results;

    QJsonObject toJson() const {
        QJsonObject obj;
        obj["id"] = id;
        obj["title"] = title;
        obj["prompt"] = prompt;
        obj["timestamp"] = timestamp.toMSecsSinceEpoch();
        QJsonArray arr;
        for (const auto &r : results)
            arr.append(r.toJson());
        obj["results"] = arr;
        return obj;
    }

    static StoredReport fromJson(const QJsonObject &obj) {
        StoredReport r;
        r.id = obj["id"].toString();
        r.title = obj["title"].toString();
        r.prompt = obj["prompt"].toString();
        r.timestamp = QDateTime::fromMSecsSinceEpoch(
            static_cast<qint64>(obj["timestamp"].toDouble()));
        for (const auto &v : obj["results"].toArray())
            r.results.append(StoredAnalysisResult::fromJson(v.toObject()));
        return r;
    }
};
