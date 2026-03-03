#pragma once

#include <optional>
#include <QString>
#include <QVector>
#include <QJsonObject>
#include <QJsonArray>
#include <QJsonValue>
#include "DataModels.h"

// ============================================================================
// Helper functions for JSON serialization
// ============================================================================

namespace JsonHelper {

inline void writeString(QJsonObject& obj, const QString& key, const QString& value) {
    obj[key] = value;
}

inline void writeOptionalString(QJsonObject& obj, const QString& key, const std::optional<QString>& value) {
    if (value.has_value()) obj[key] = value.value();
}

inline void writeOptionalInt(QJsonObject& obj, const QString& key, const std::optional<int>& value) {
    if (value.has_value()) obj[key] = value.value();
}

inline void writeOptionalInt64(QJsonObject& obj, const QString& key, const std::optional<qint64>& value) {
    if (value.has_value()) obj[key] = value.value();
}

inline void writeOptionalDouble(QJsonObject& obj, const QString& key, const std::optional<double>& value) {
    if (value.has_value()) obj[key] = value.value();
}

inline void writeOptionalFloat(QJsonObject& obj, const QString& key, const std::optional<float>& value) {
    if (value.has_value()) obj[key] = static_cast<double>(value.value());
}

inline void writeOptionalBool(QJsonObject& obj, const QString& key, const std::optional<bool>& value) {
    if (value.has_value()) obj[key] = value.value();
}

inline void writeOptionalStringList(QJsonObject& obj, const QString& key, const std::optional<QVector<QString>>& value) {
    if (value.has_value()) {
        QJsonArray arr;
        for (const auto& s : value.value()) arr.append(s);
        obj[key] = arr;
    }
}

inline std::optional<QString> readOptionalString(const QJsonObject& obj, const QString& key) {
    if (obj.contains(key) && !obj[key].isNull()) return obj[key].toString();
    return std::nullopt;
}

inline std::optional<int> readOptionalInt(const QJsonObject& obj, const QString& key) {
    if (obj.contains(key) && !obj[key].isNull()) return obj[key].toInt();
    return std::nullopt;
}

inline std::optional<qint64> readOptionalInt64(const QJsonObject& obj, const QString& key) {
    if (obj.contains(key) && !obj[key].isNull()) return static_cast<qint64>(obj[key].toDouble());
    return std::nullopt;
}

inline std::optional<double> readOptionalDouble(const QJsonObject& obj, const QString& key) {
    if (obj.contains(key) && !obj[key].isNull()) return obj[key].toDouble();
    return std::nullopt;
}

inline std::optional<float> readOptionalFloat(const QJsonObject& obj, const QString& key) {
    if (obj.contains(key) && !obj[key].isNull()) return static_cast<float>(obj[key].toDouble());
    return std::nullopt;
}

inline std::optional<bool> readOptionalBool(const QJsonObject& obj, const QString& key) {
    if (obj.contains(key) && !obj[key].isNull()) return obj[key].toBool();
    return std::nullopt;
}

inline std::optional<QVector<QString>> readOptionalStringList(const QJsonObject& obj, const QString& key) {
    if (obj.contains(key) && obj[key].isArray()) {
        QVector<QString> result;
        for (const auto& v : obj[key].toArray()) result.append(v.toString());
        return result;
    }
    return std::nullopt;
}

} // namespace JsonHelper

// ============================================================================
// OpenAI-Compatible Models
// ============================================================================

struct OpenAiMessage {
    QString role;
    std::optional<QString> content;
    std::optional<QString> reasoning_content; // DeepSeek reasoning models

    QJsonObject toJson() const {
        QJsonObject obj;
        obj[QStringLiteral("role")] = role;
        if (content.has_value())
            obj[QStringLiteral("content")] = content.value();
        else
            obj[QStringLiteral("content")] = QJsonValue(QJsonValue::Null);
        JsonHelper::writeOptionalString(obj, QStringLiteral("reasoning_content"), reasoning_content);
        return obj;
    }

    static OpenAiMessage fromJson(const QJsonObject& obj) {
        OpenAiMessage msg;
        msg.role = obj[QStringLiteral("role")].toString();
        if (obj.contains(QStringLiteral("content")) && !obj[QStringLiteral("content")].isNull())
            msg.content = obj[QStringLiteral("content")].toString();
        msg.reasoning_content = JsonHelper::readOptionalString(obj, QStringLiteral("reasoning_content"));
        return msg;
    }
};

struct OpenAiResponseFormat {
    QString type = QStringLiteral("text"); // "text" or "json_object"

    QJsonObject toJson() const {
        QJsonObject obj;
        obj[QStringLiteral("type")] = type;
        return obj;
    }

    static OpenAiResponseFormat fromJson(const QJsonObject& obj) {
        OpenAiResponseFormat fmt;
        if (obj.contains(QStringLiteral("type")))
            fmt.type = obj[QStringLiteral("type")].toString();
        return fmt;
    }
};

struct OpenAiRequest {
    QString model;
    QVector<OpenAiMessage> messages;
    std::optional<int> max_tokens;
    std::optional<float> temperature;
    std::optional<float> top_p;
    std::optional<int> top_k;
    std::optional<float> frequency_penalty;
    std::optional<float> presence_penalty;
    std::optional<QVector<QString>> stop;
    std::optional<int> seed;
    std::optional<int> random_seed;          // Mistral uses random_seed instead of seed
    std::optional<OpenAiResponseFormat> response_format;
    std::optional<bool> return_citations;    // Perplexity
    std::optional<QString> search_recency_filter; // Perplexity: "day", "week", "month", "year"
    std::optional<bool> search;              // Web search

    QJsonObject toJson() const {
        QJsonObject obj;
        obj[QStringLiteral("model")] = model;
        QJsonArray msgsArr;
        for (const auto& m : messages) msgsArr.append(m.toJson());
        obj[QStringLiteral("messages")] = msgsArr;
        JsonHelper::writeOptionalInt(obj, QStringLiteral("max_tokens"), max_tokens);
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("temperature"), temperature);
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("top_p"), top_p);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("top_k"), top_k);
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("frequency_penalty"), frequency_penalty);
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("presence_penalty"), presence_penalty);
        JsonHelper::writeOptionalStringList(obj, QStringLiteral("stop"), stop);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("seed"), seed);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("random_seed"), random_seed);
        if (response_format.has_value())
            obj[QStringLiteral("response_format")] = response_format->toJson();
        JsonHelper::writeOptionalBool(obj, QStringLiteral("return_citations"), return_citations);
        JsonHelper::writeOptionalString(obj, QStringLiteral("search_recency_filter"), search_recency_filter);
        JsonHelper::writeOptionalBool(obj, QStringLiteral("search"), search);
        return obj;
    }

    static OpenAiRequest fromJson(const QJsonObject& obj) {
        OpenAiRequest req;
        req.model = obj[QStringLiteral("model")].toString();
        if (obj.contains(QStringLiteral("messages"))) {
            for (const auto& v : obj[QStringLiteral("messages")].toArray())
                req.messages.append(OpenAiMessage::fromJson(v.toObject()));
        }
        req.max_tokens = JsonHelper::readOptionalInt(obj, QStringLiteral("max_tokens"));
        req.temperature = JsonHelper::readOptionalFloat(obj, QStringLiteral("temperature"));
        req.top_p = JsonHelper::readOptionalFloat(obj, QStringLiteral("top_p"));
        req.top_k = JsonHelper::readOptionalInt(obj, QStringLiteral("top_k"));
        req.frequency_penalty = JsonHelper::readOptionalFloat(obj, QStringLiteral("frequency_penalty"));
        req.presence_penalty = JsonHelper::readOptionalFloat(obj, QStringLiteral("presence_penalty"));
        req.stop = JsonHelper::readOptionalStringList(obj, QStringLiteral("stop"));
        req.seed = JsonHelper::readOptionalInt(obj, QStringLiteral("seed"));
        req.random_seed = JsonHelper::readOptionalInt(obj, QStringLiteral("random_seed"));
        if (obj.contains(QStringLiteral("response_format")) && obj[QStringLiteral("response_format")].isObject())
            req.response_format = OpenAiResponseFormat::fromJson(obj[QStringLiteral("response_format")].toObject());
        req.return_citations = JsonHelper::readOptionalBool(obj, QStringLiteral("return_citations"));
        req.search_recency_filter = JsonHelper::readOptionalString(obj, QStringLiteral("search_recency_filter"));
        req.search = JsonHelper::readOptionalBool(obj, QStringLiteral("search"));
        return req;
    }
};

struct OpenAiStreamRequest {
    QString model;
    QVector<OpenAiMessage> messages;
    bool stream = true;
    std::optional<int> max_tokens;
    std::optional<float> temperature;
    std::optional<float> top_p;
    std::optional<int> top_k;
    std::optional<float> frequency_penalty;
    std::optional<float> presence_penalty;
    std::optional<QVector<QString>> stop;
    std::optional<int> seed;
    std::optional<int> random_seed;
    std::optional<OpenAiResponseFormat> response_format;
    std::optional<bool> return_citations;
    std::optional<QString> search_recency_filter;
    std::optional<bool> search;

    QJsonObject toJson() const {
        QJsonObject obj;
        obj[QStringLiteral("model")] = model;
        QJsonArray msgsArr;
        for (const auto& m : messages) msgsArr.append(m.toJson());
        obj[QStringLiteral("messages")] = msgsArr;
        obj[QStringLiteral("stream")] = stream;
        JsonHelper::writeOptionalInt(obj, QStringLiteral("max_tokens"), max_tokens);
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("temperature"), temperature);
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("top_p"), top_p);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("top_k"), top_k);
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("frequency_penalty"), frequency_penalty);
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("presence_penalty"), presence_penalty);
        JsonHelper::writeOptionalStringList(obj, QStringLiteral("stop"), stop);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("seed"), seed);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("random_seed"), random_seed);
        if (response_format.has_value())
            obj[QStringLiteral("response_format")] = response_format->toJson();
        JsonHelper::writeOptionalBool(obj, QStringLiteral("return_citations"), return_citations);
        JsonHelper::writeOptionalString(obj, QStringLiteral("search_recency_filter"), search_recency_filter);
        JsonHelper::writeOptionalBool(obj, QStringLiteral("search"), search);
        return obj;
    }

    static OpenAiStreamRequest fromJson(const QJsonObject& obj) {
        OpenAiStreamRequest req;
        req.model = obj[QStringLiteral("model")].toString();
        if (obj.contains(QStringLiteral("messages"))) {
            for (const auto& v : obj[QStringLiteral("messages")].toArray())
                req.messages.append(OpenAiMessage::fromJson(v.toObject()));
        }
        if (obj.contains(QStringLiteral("stream")))
            req.stream = obj[QStringLiteral("stream")].toBool(true);
        req.max_tokens = JsonHelper::readOptionalInt(obj, QStringLiteral("max_tokens"));
        req.temperature = JsonHelper::readOptionalFloat(obj, QStringLiteral("temperature"));
        req.top_p = JsonHelper::readOptionalFloat(obj, QStringLiteral("top_p"));
        req.top_k = JsonHelper::readOptionalInt(obj, QStringLiteral("top_k"));
        req.frequency_penalty = JsonHelper::readOptionalFloat(obj, QStringLiteral("frequency_penalty"));
        req.presence_penalty = JsonHelper::readOptionalFloat(obj, QStringLiteral("presence_penalty"));
        req.stop = JsonHelper::readOptionalStringList(obj, QStringLiteral("stop"));
        req.seed = JsonHelper::readOptionalInt(obj, QStringLiteral("seed"));
        req.random_seed = JsonHelper::readOptionalInt(obj, QStringLiteral("random_seed"));
        if (obj.contains(QStringLiteral("response_format")) && obj[QStringLiteral("response_format")].isObject())
            req.response_format = OpenAiResponseFormat::fromJson(obj[QStringLiteral("response_format")].toObject());
        req.return_citations = JsonHelper::readOptionalBool(obj, QStringLiteral("return_citations"));
        req.search_recency_filter = JsonHelper::readOptionalString(obj, QStringLiteral("search_recency_filter"));
        req.search = JsonHelper::readOptionalBool(obj, QStringLiteral("search"));
        return req;
    }
};

struct OpenAiChoice {
    OpenAiMessage message;
    int index = 0;

    QJsonObject toJson() const {
        QJsonObject obj;
        obj[QStringLiteral("message")] = message.toJson();
        obj[QStringLiteral("index")] = index;
        return obj;
    }

    static OpenAiChoice fromJson(const QJsonObject& obj) {
        OpenAiChoice choice;
        if (obj.contains(QStringLiteral("message")))
            choice.message = OpenAiMessage::fromJson(obj[QStringLiteral("message")].toObject());
        if (obj.contains(QStringLiteral("index")))
            choice.index = obj[QStringLiteral("index")].toInt();
        return choice;
    }
};

/// Cost object structure (used by Perplexity)
struct UsageCost {
    std::optional<double> total_cost;

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalDouble(obj, QStringLiteral("total_cost"), total_cost);
        return obj;
    }

    static UsageCost fromJson(const QJsonObject& obj) {
        UsageCost cost;
        cost.total_cost = JsonHelper::readOptionalDouble(obj, QStringLiteral("total_cost"));
        return cost;
    }
};

/// Flexible cost that can be either a Double (OpenRouter) or an object with total_cost (Perplexity).
struct FlexibleCost {
    enum class Type { Double, Object };
    Type type = Type::Double;
    double doubleValue = 0.0;
    UsageCost objectValue;

    /// Get the effective numeric cost value
    std::optional<double> value() const {
        if (type == Type::Double) return doubleValue;
        return objectValue.total_cost;
    }

    QJsonObject toJson() const {
        // FlexibleCost is not typically serialized to a request; included for completeness
        QJsonObject obj;
        if (type == Type::Double)
            obj[QStringLiteral("cost")] = doubleValue;
        else
            obj[QStringLiteral("cost")] = objectValue.toJson();
        return obj;
    }

    static FlexibleCost fromJsonValue(const QJsonValue& val) {
        FlexibleCost cost;
        if (val.isDouble()) {
            cost.type = Type::Double;
            cost.doubleValue = val.toDouble();
        } else if (val.isObject()) {
            cost.type = Type::Object;
            cost.objectValue = UsageCost::fromJson(val.toObject());
        }
        return cost;
    }
};

struct OpenAiUsage {
    // Chat Completions API uses prompt_tokens/completion_tokens
    std::optional<int> prompt_tokens;
    std::optional<int> completion_tokens;
    std::optional<int> total_tokens;
    // Responses API uses input_tokens/output_tokens
    std::optional<int> input_tokens;
    std::optional<int> output_tokens;
    // Cost variations from different providers
    std::optional<FlexibleCost> cost;              // Double (OpenRouter) or object (Perplexity)
    std::optional<qint64> cost_in_usd_ticks;       // xAI: cost in billionths of a dollar
    std::optional<UsageCost> cost_usd;              // Alternative Perplexity cost field

    /// Effective input token count (prefers input_tokens from Responses API)
    std::optional<int> effectiveInputTokens() const { return input_tokens.has_value() ? input_tokens : prompt_tokens; }
    /// Effective output token count (prefers output_tokens from Responses API)
    std::optional<int> effectiveOutputTokens() const { return output_tokens.has_value() ? output_tokens : completion_tokens; }

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalInt(obj, QStringLiteral("prompt_tokens"), prompt_tokens);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("completion_tokens"), completion_tokens);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("total_tokens"), total_tokens);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("input_tokens"), input_tokens);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("output_tokens"), output_tokens);
        if (cost.has_value()) {
            if (cost->type == FlexibleCost::Type::Double)
                obj[QStringLiteral("cost")] = cost->doubleValue;
            else
                obj[QStringLiteral("cost")] = cost->objectValue.toJson();
        }
        JsonHelper::writeOptionalInt64(obj, QStringLiteral("cost_in_usd_ticks"), cost_in_usd_ticks);
        if (cost_usd.has_value())
            obj[QStringLiteral("cost_usd")] = cost_usd->toJson();
        return obj;
    }

    static OpenAiUsage fromJson(const QJsonObject& obj) {
        OpenAiUsage usage;
        usage.prompt_tokens = JsonHelper::readOptionalInt(obj, QStringLiteral("prompt_tokens"));
        usage.completion_tokens = JsonHelper::readOptionalInt(obj, QStringLiteral("completion_tokens"));
        usage.total_tokens = JsonHelper::readOptionalInt(obj, QStringLiteral("total_tokens"));
        usage.input_tokens = JsonHelper::readOptionalInt(obj, QStringLiteral("input_tokens"));
        usage.output_tokens = JsonHelper::readOptionalInt(obj, QStringLiteral("output_tokens"));
        if (obj.contains(QStringLiteral("cost")) && !obj[QStringLiteral("cost")].isNull())
            usage.cost = FlexibleCost::fromJsonValue(obj[QStringLiteral("cost")]);
        usage.cost_in_usd_ticks = JsonHelper::readOptionalInt64(obj, QStringLiteral("cost_in_usd_ticks"));
        if (obj.contains(QStringLiteral("cost_usd")) && obj[QStringLiteral("cost_usd")].isObject())
            usage.cost_usd = UsageCost::fromJson(obj[QStringLiteral("cost_usd")].toObject());
        return usage;
    }
};

struct OpenAiError {
    std::optional<QString> message;
    std::optional<QString> type;

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalString(obj, QStringLiteral("message"), message);
        JsonHelper::writeOptionalString(obj, QStringLiteral("type"), type);
        return obj;
    }

    static OpenAiError fromJson(const QJsonObject& obj) {
        OpenAiError err;
        err.message = JsonHelper::readOptionalString(obj, QStringLiteral("message"));
        err.type = JsonHelper::readOptionalString(obj, QStringLiteral("type"));
        return err;
    }
};

struct OpenAiResponse {
    std::optional<QString> id;
    std::optional<QVector<OpenAiChoice>> choices;
    std::optional<OpenAiUsage> usage;
    std::optional<OpenAiError> error;
    std::optional<QVector<QString>> citations;         // Perplexity returns citations as URLs
    std::optional<QVector<SearchResult>> search_results;
    std::optional<QVector<QString>> related_questions;  // Perplexity returns follow-up questions

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalString(obj, QStringLiteral("id"), id);
        if (choices.has_value()) {
            QJsonArray arr;
            for (const auto& c : choices.value()) arr.append(c.toJson());
            obj[QStringLiteral("choices")] = arr;
        }
        if (usage.has_value())
            obj[QStringLiteral("usage")] = usage->toJson();
        if (error.has_value())
            obj[QStringLiteral("error")] = error->toJson();
        JsonHelper::writeOptionalStringList(obj, QStringLiteral("citations"), citations);
        if (search_results.has_value()) {
            QJsonArray arr;
            for (const auto& sr : search_results.value()) arr.append(sr.toJson());
            obj[QStringLiteral("search_results")] = arr;
        }
        JsonHelper::writeOptionalStringList(obj, QStringLiteral("related_questions"), related_questions);
        return obj;
    }

    static OpenAiResponse fromJson(const QJsonObject& obj) {
        OpenAiResponse resp;
        resp.id = JsonHelper::readOptionalString(obj, QStringLiteral("id"));
        if (obj.contains(QStringLiteral("choices")) && obj[QStringLiteral("choices")].isArray()) {
            QVector<OpenAiChoice> vec;
            for (const auto& v : obj[QStringLiteral("choices")].toArray())
                vec.append(OpenAiChoice::fromJson(v.toObject()));
            resp.choices = vec;
        }
        if (obj.contains(QStringLiteral("usage")) && obj[QStringLiteral("usage")].isObject())
            resp.usage = OpenAiUsage::fromJson(obj[QStringLiteral("usage")].toObject());
        if (obj.contains(QStringLiteral("error")) && obj[QStringLiteral("error")].isObject())
            resp.error = OpenAiError::fromJson(obj[QStringLiteral("error")].toObject());
        resp.citations = JsonHelper::readOptionalStringList(obj, QStringLiteral("citations"));
        if (obj.contains(QStringLiteral("search_results")) && obj[QStringLiteral("search_results")].isArray()) {
            QVector<SearchResult> vec;
            for (const auto& v : obj[QStringLiteral("search_results")].toArray())
                vec.append(SearchResult::fromJson(v.toObject()));
            resp.search_results = vec;
        }
        resp.related_questions = JsonHelper::readOptionalStringList(obj, QStringLiteral("related_questions"));
        return resp;
    }
};

// ============================================================================
// OpenAI Streaming Models
// ============================================================================

struct StreamDelta {
    std::optional<QString> role;
    std::optional<QString> content;
    std::optional<QString> reasoning_content; // DeepSeek reasoning models

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalString(obj, QStringLiteral("role"), role);
        JsonHelper::writeOptionalString(obj, QStringLiteral("content"), content);
        JsonHelper::writeOptionalString(obj, QStringLiteral("reasoning_content"), reasoning_content);
        return obj;
    }

    static StreamDelta fromJson(const QJsonObject& obj) {
        StreamDelta d;
        d.role = JsonHelper::readOptionalString(obj, QStringLiteral("role"));
        d.content = JsonHelper::readOptionalString(obj, QStringLiteral("content"));
        d.reasoning_content = JsonHelper::readOptionalString(obj, QStringLiteral("reasoning_content"));
        return d;
    }
};

struct StreamChoice {
    std::optional<int> index;
    std::optional<StreamDelta> delta;
    std::optional<QString> finish_reason;

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalInt(obj, QStringLiteral("index"), index);
        if (delta.has_value())
            obj[QStringLiteral("delta")] = delta->toJson();
        JsonHelper::writeOptionalString(obj, QStringLiteral("finish_reason"), finish_reason);
        return obj;
    }

    static StreamChoice fromJson(const QJsonObject& obj) {
        StreamChoice c;
        c.index = JsonHelper::readOptionalInt(obj, QStringLiteral("index"));
        if (obj.contains(QStringLiteral("delta")) && obj[QStringLiteral("delta")].isObject())
            c.delta = StreamDelta::fromJson(obj[QStringLiteral("delta")].toObject());
        c.finish_reason = JsonHelper::readOptionalString(obj, QStringLiteral("finish_reason"));
        return c;
    }
};

struct OpenAiStreamChunk {
    std::optional<QString> id;
    std::optional<QVector<StreamChoice>> choices;
    std::optional<qint64> created;

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalString(obj, QStringLiteral("id"), id);
        if (choices.has_value()) {
            QJsonArray arr;
            for (const auto& c : choices.value()) arr.append(c.toJson());
            obj[QStringLiteral("choices")] = arr;
        }
        JsonHelper::writeOptionalInt64(obj, QStringLiteral("created"), created);
        return obj;
    }

    static OpenAiStreamChunk fromJson(const QJsonObject& obj) {
        OpenAiStreamChunk chunk;
        chunk.id = JsonHelper::readOptionalString(obj, QStringLiteral("id"));
        if (obj.contains(QStringLiteral("choices")) && obj[QStringLiteral("choices")].isArray()) {
            QVector<StreamChoice> vec;
            for (const auto& v : obj[QStringLiteral("choices")].toArray())
                vec.append(StreamChoice::fromJson(v.toObject()));
            chunk.choices = vec;
        }
        chunk.created = JsonHelper::readOptionalInt64(obj, QStringLiteral("created"));
        return chunk;
    }
};

// ============================================================================
// OpenAI Responses API Models (for GPT-5.x/o3/o4)
// ============================================================================

struct OpenAiResponsesInputMessage {
    QString role;
    QString content;

    QJsonObject toJson() const {
        QJsonObject obj;
        obj[QStringLiteral("role")] = role;
        obj[QStringLiteral("content")] = content;
        return obj;
    }

    static OpenAiResponsesInputMessage fromJson(const QJsonObject& obj) {
        OpenAiResponsesInputMessage msg;
        msg.role = obj[QStringLiteral("role")].toString();
        msg.content = obj[QStringLiteral("content")].toString();
        return msg;
    }
};

/// OpenAI Responses API request. Input can be a string or array of messages.
struct OpenAiResponsesRequest {
    QString model;
    // Input stored in two forms; exactly one should be used
    std::optional<QString> inputString;
    std::optional<QVector<OpenAiResponsesInputMessage>> inputMessages;
    std::optional<QString> instructions;

    QJsonObject toJson() const {
        QJsonObject obj;
        obj[QStringLiteral("model")] = model;
        if (inputString.has_value()) {
            obj[QStringLiteral("input")] = inputString.value();
        } else if (inputMessages.has_value()) {
            QJsonArray arr;
            for (const auto& m : inputMessages.value()) arr.append(m.toJson());
            obj[QStringLiteral("input")] = arr;
        }
        JsonHelper::writeOptionalString(obj, QStringLiteral("instructions"), instructions);
        return obj;
    }

    static OpenAiResponsesRequest fromJson(const QJsonObject& obj) {
        OpenAiResponsesRequest req;
        req.model = obj[QStringLiteral("model")].toString();
        if (obj.contains(QStringLiteral("input"))) {
            const auto& val = obj[QStringLiteral("input")];
            if (val.isString()) {
                req.inputString = val.toString();
            } else if (val.isArray()) {
                QVector<OpenAiResponsesInputMessage> vec;
                for (const auto& v : val.toArray())
                    vec.append(OpenAiResponsesInputMessage::fromJson(v.toObject()));
                req.inputMessages = vec;
            }
        }
        req.instructions = JsonHelper::readOptionalString(obj, QStringLiteral("instructions"));
        return req;
    }
};

struct OpenAiResponsesStreamRequest {
    QString model;
    QVector<OpenAiResponsesInputMessage> input;
    std::optional<QString> instructions;
    bool stream = true;

    QJsonObject toJson() const {
        QJsonObject obj;
        obj[QStringLiteral("model")] = model;
        QJsonArray arr;
        for (const auto& m : input) arr.append(m.toJson());
        obj[QStringLiteral("input")] = arr;
        JsonHelper::writeOptionalString(obj, QStringLiteral("instructions"), instructions);
        obj[QStringLiteral("stream")] = stream;
        return obj;
    }

    static OpenAiResponsesStreamRequest fromJson(const QJsonObject& obj) {
        OpenAiResponsesStreamRequest req;
        req.model = obj[QStringLiteral("model")].toString();
        if (obj.contains(QStringLiteral("input")) && obj[QStringLiteral("input")].isArray()) {
            for (const auto& v : obj[QStringLiteral("input")].toArray())
                req.input.append(OpenAiResponsesInputMessage::fromJson(v.toObject()));
        }
        req.instructions = JsonHelper::readOptionalString(obj, QStringLiteral("instructions"));
        if (obj.contains(QStringLiteral("stream")))
            req.stream = obj[QStringLiteral("stream")].toBool(true);
        return req;
    }
};

struct OpenAiResponsesOutputContent {
    std::optional<QString> type;
    std::optional<QString> text;

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalString(obj, QStringLiteral("type"), type);
        JsonHelper::writeOptionalString(obj, QStringLiteral("text"), text);
        return obj;
    }

    static OpenAiResponsesOutputContent fromJson(const QJsonObject& obj) {
        OpenAiResponsesOutputContent c;
        c.type = JsonHelper::readOptionalString(obj, QStringLiteral("type"));
        c.text = JsonHelper::readOptionalString(obj, QStringLiteral("text"));
        return c;
    }
};

struct OpenAiResponsesOutputMessage {
    std::optional<QString> type;
    std::optional<QString> id;
    std::optional<QString> status;
    std::optional<QString> role;
    std::optional<QVector<OpenAiResponsesOutputContent>> content;

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalString(obj, QStringLiteral("type"), type);
        JsonHelper::writeOptionalString(obj, QStringLiteral("id"), id);
        JsonHelper::writeOptionalString(obj, QStringLiteral("status"), status);
        JsonHelper::writeOptionalString(obj, QStringLiteral("role"), role);
        if (content.has_value()) {
            QJsonArray arr;
            for (const auto& c : content.value()) arr.append(c.toJson());
            obj[QStringLiteral("content")] = arr;
        }
        return obj;
    }

    static OpenAiResponsesOutputMessage fromJson(const QJsonObject& obj) {
        OpenAiResponsesOutputMessage msg;
        msg.type = JsonHelper::readOptionalString(obj, QStringLiteral("type"));
        msg.id = JsonHelper::readOptionalString(obj, QStringLiteral("id"));
        msg.status = JsonHelper::readOptionalString(obj, QStringLiteral("status"));
        msg.role = JsonHelper::readOptionalString(obj, QStringLiteral("role"));
        if (obj.contains(QStringLiteral("content")) && obj[QStringLiteral("content")].isArray()) {
            QVector<OpenAiResponsesOutputContent> vec;
            for (const auto& v : obj[QStringLiteral("content")].toArray())
                vec.append(OpenAiResponsesOutputContent::fromJson(v.toObject()));
            msg.content = vec;
        }
        return msg;
    }
};

struct OpenAiResponsesError {
    std::optional<QString> message;
    std::optional<QString> type;
    std::optional<QString> code;

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalString(obj, QStringLiteral("message"), message);
        JsonHelper::writeOptionalString(obj, QStringLiteral("type"), type);
        JsonHelper::writeOptionalString(obj, QStringLiteral("code"), code);
        return obj;
    }

    static OpenAiResponsesError fromJson(const QJsonObject& obj) {
        OpenAiResponsesError err;
        err.message = JsonHelper::readOptionalString(obj, QStringLiteral("message"));
        err.type = JsonHelper::readOptionalString(obj, QStringLiteral("type"));
        err.code = JsonHelper::readOptionalString(obj, QStringLiteral("code"));
        return err;
    }
};

struct OpenAiResponsesApiResponse {
    std::optional<QString> id;
    std::optional<QString> status;
    std::optional<OpenAiResponsesError> error;
    std::optional<QVector<OpenAiResponsesOutputMessage>> output;
    std::optional<OpenAiUsage> usage;

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalString(obj, QStringLiteral("id"), id);
        JsonHelper::writeOptionalString(obj, QStringLiteral("status"), status);
        if (error.has_value())
            obj[QStringLiteral("error")] = error->toJson();
        if (output.has_value()) {
            QJsonArray arr;
            for (const auto& m : output.value()) arr.append(m.toJson());
            obj[QStringLiteral("output")] = arr;
        }
        if (usage.has_value())
            obj[QStringLiteral("usage")] = usage->toJson();
        return obj;
    }

    static OpenAiResponsesApiResponse fromJson(const QJsonObject& obj) {
        OpenAiResponsesApiResponse resp;
        resp.id = JsonHelper::readOptionalString(obj, QStringLiteral("id"));
        resp.status = JsonHelper::readOptionalString(obj, QStringLiteral("status"));
        if (obj.contains(QStringLiteral("error")) && obj[QStringLiteral("error")].isObject())
            resp.error = OpenAiResponsesError::fromJson(obj[QStringLiteral("error")].toObject());
        if (obj.contains(QStringLiteral("output")) && obj[QStringLiteral("output")].isArray()) {
            QVector<OpenAiResponsesOutputMessage> vec;
            for (const auto& v : obj[QStringLiteral("output")].toArray())
                vec.append(OpenAiResponsesOutputMessage::fromJson(v.toObject()));
            resp.output = vec;
        }
        if (obj.contains(QStringLiteral("usage")) && obj[QStringLiteral("usage")].isObject())
            resp.usage = OpenAiUsage::fromJson(obj[QStringLiteral("usage")].toObject());
        return resp;
    }
};

// ============================================================================
// Anthropic Claude Models
// ============================================================================

struct ClaudeMessage {
    QString role;
    QString content;

    QJsonObject toJson() const {
        QJsonObject obj;
        obj[QStringLiteral("role")] = role;
        obj[QStringLiteral("content")] = content;
        return obj;
    }

    static ClaudeMessage fromJson(const QJsonObject& obj) {
        ClaudeMessage msg;
        msg.role = obj[QStringLiteral("role")].toString();
        msg.content = obj[QStringLiteral("content")].toString();
        return msg;
    }
};

struct ClaudeRequest {
    QString model;
    std::optional<int> max_tokens = 4096; // Required by Anthropic, defaults to 4096
    QVector<ClaudeMessage> messages;
    std::optional<float> temperature;
    std::optional<float> top_p;
    std::optional<int> top_k;
    std::optional<QString> system;
    std::optional<QVector<QString>> stop_sequences;
    std::optional<float> frequency_penalty;
    std::optional<float> presence_penalty;
    std::optional<int> seed;
    std::optional<bool> search;

    QJsonObject toJson() const {
        QJsonObject obj;
        obj[QStringLiteral("model")] = model;
        // max_tokens is required by Anthropic API
        obj[QStringLiteral("max_tokens")] = max_tokens.value_or(4096);
        QJsonArray msgsArr;
        for (const auto& m : messages) msgsArr.append(m.toJson());
        obj[QStringLiteral("messages")] = msgsArr;
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("temperature"), temperature);
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("top_p"), top_p);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("top_k"), top_k);
        JsonHelper::writeOptionalString(obj, QStringLiteral("system"), system);
        JsonHelper::writeOptionalStringList(obj, QStringLiteral("stop_sequences"), stop_sequences);
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("frequency_penalty"), frequency_penalty);
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("presence_penalty"), presence_penalty);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("seed"), seed);
        JsonHelper::writeOptionalBool(obj, QStringLiteral("search"), search);
        return obj;
    }

    static ClaudeRequest fromJson(const QJsonObject& obj) {
        ClaudeRequest req;
        req.model = obj[QStringLiteral("model")].toString();
        req.max_tokens = JsonHelper::readOptionalInt(obj, QStringLiteral("max_tokens"));
        if (!req.max_tokens.has_value()) req.max_tokens = 4096;
        if (obj.contains(QStringLiteral("messages"))) {
            for (const auto& v : obj[QStringLiteral("messages")].toArray())
                req.messages.append(ClaudeMessage::fromJson(v.toObject()));
        }
        req.temperature = JsonHelper::readOptionalFloat(obj, QStringLiteral("temperature"));
        req.top_p = JsonHelper::readOptionalFloat(obj, QStringLiteral("top_p"));
        req.top_k = JsonHelper::readOptionalInt(obj, QStringLiteral("top_k"));
        req.system = JsonHelper::readOptionalString(obj, QStringLiteral("system"));
        req.stop_sequences = JsonHelper::readOptionalStringList(obj, QStringLiteral("stop_sequences"));
        req.frequency_penalty = JsonHelper::readOptionalFloat(obj, QStringLiteral("frequency_penalty"));
        req.presence_penalty = JsonHelper::readOptionalFloat(obj, QStringLiteral("presence_penalty"));
        req.seed = JsonHelper::readOptionalInt(obj, QStringLiteral("seed"));
        req.search = JsonHelper::readOptionalBool(obj, QStringLiteral("search"));
        return req;
    }
};

struct ClaudeStreamRequest {
    QString model;
    QVector<ClaudeMessage> messages;
    bool stream = true;
    int max_tokens = 4096; // Required by Anthropic, non-optional
    std::optional<float> temperature;
    std::optional<float> top_p;
    std::optional<int> top_k;
    std::optional<QString> system;
    std::optional<QVector<QString>> stop_sequences;
    std::optional<float> frequency_penalty;
    std::optional<float> presence_penalty;
    std::optional<int> seed;
    std::optional<bool> search;

    QJsonObject toJson() const {
        QJsonObject obj;
        obj[QStringLiteral("model")] = model;
        QJsonArray msgsArr;
        for (const auto& m : messages) msgsArr.append(m.toJson());
        obj[QStringLiteral("messages")] = msgsArr;
        obj[QStringLiteral("stream")] = stream;
        obj[QStringLiteral("max_tokens")] = max_tokens;
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("temperature"), temperature);
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("top_p"), top_p);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("top_k"), top_k);
        JsonHelper::writeOptionalString(obj, QStringLiteral("system"), system);
        JsonHelper::writeOptionalStringList(obj, QStringLiteral("stop_sequences"), stop_sequences);
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("frequency_penalty"), frequency_penalty);
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("presence_penalty"), presence_penalty);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("seed"), seed);
        JsonHelper::writeOptionalBool(obj, QStringLiteral("search"), search);
        return obj;
    }

    static ClaudeStreamRequest fromJson(const QJsonObject& obj) {
        ClaudeStreamRequest req;
        req.model = obj[QStringLiteral("model")].toString();
        if (obj.contains(QStringLiteral("messages"))) {
            for (const auto& v : obj[QStringLiteral("messages")].toArray())
                req.messages.append(ClaudeMessage::fromJson(v.toObject()));
        }
        if (obj.contains(QStringLiteral("stream")))
            req.stream = obj[QStringLiteral("stream")].toBool(true);
        if (obj.contains(QStringLiteral("max_tokens")))
            req.max_tokens = obj[QStringLiteral("max_tokens")].toInt(4096);
        req.temperature = JsonHelper::readOptionalFloat(obj, QStringLiteral("temperature"));
        req.top_p = JsonHelper::readOptionalFloat(obj, QStringLiteral("top_p"));
        req.top_k = JsonHelper::readOptionalInt(obj, QStringLiteral("top_k"));
        req.system = JsonHelper::readOptionalString(obj, QStringLiteral("system"));
        req.stop_sequences = JsonHelper::readOptionalStringList(obj, QStringLiteral("stop_sequences"));
        req.frequency_penalty = JsonHelper::readOptionalFloat(obj, QStringLiteral("frequency_penalty"));
        req.presence_penalty = JsonHelper::readOptionalFloat(obj, QStringLiteral("presence_penalty"));
        req.seed = JsonHelper::readOptionalInt(obj, QStringLiteral("seed"));
        req.search = JsonHelper::readOptionalBool(obj, QStringLiteral("search"));
        return req;
    }
};

struct ClaudeContentBlock {
    QString type;
    std::optional<QString> text;

    QJsonObject toJson() const {
        QJsonObject obj;
        obj[QStringLiteral("type")] = type;
        JsonHelper::writeOptionalString(obj, QStringLiteral("text"), text);
        return obj;
    }

    static ClaudeContentBlock fromJson(const QJsonObject& obj) {
        ClaudeContentBlock block;
        block.type = obj[QStringLiteral("type")].toString();
        block.text = JsonHelper::readOptionalString(obj, QStringLiteral("text"));
        return block;
    }
};

struct ClaudeUsage {
    std::optional<int> input_tokens;
    std::optional<int> output_tokens;
    std::optional<double> cost;
    std::optional<qint64> cost_in_usd_ticks;
    std::optional<UsageCost> cost_usd;

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalInt(obj, QStringLiteral("input_tokens"), input_tokens);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("output_tokens"), output_tokens);
        JsonHelper::writeOptionalDouble(obj, QStringLiteral("cost"), cost);
        JsonHelper::writeOptionalInt64(obj, QStringLiteral("cost_in_usd_ticks"), cost_in_usd_ticks);
        if (cost_usd.has_value())
            obj[QStringLiteral("cost_usd")] = cost_usd->toJson();
        return obj;
    }

    static ClaudeUsage fromJson(const QJsonObject& obj) {
        ClaudeUsage usage;
        usage.input_tokens = JsonHelper::readOptionalInt(obj, QStringLiteral("input_tokens"));
        usage.output_tokens = JsonHelper::readOptionalInt(obj, QStringLiteral("output_tokens"));
        usage.cost = JsonHelper::readOptionalDouble(obj, QStringLiteral("cost"));
        usage.cost_in_usd_ticks = JsonHelper::readOptionalInt64(obj, QStringLiteral("cost_in_usd_ticks"));
        if (obj.contains(QStringLiteral("cost_usd")) && obj[QStringLiteral("cost_usd")].isObject())
            usage.cost_usd = UsageCost::fromJson(obj[QStringLiteral("cost_usd")].toObject());
        return usage;
    }
};

struct ClaudeError {
    std::optional<QString> type;
    std::optional<QString> message;

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalString(obj, QStringLiteral("type"), type);
        JsonHelper::writeOptionalString(obj, QStringLiteral("message"), message);
        return obj;
    }

    static ClaudeError fromJson(const QJsonObject& obj) {
        ClaudeError err;
        err.type = JsonHelper::readOptionalString(obj, QStringLiteral("type"));
        err.message = JsonHelper::readOptionalString(obj, QStringLiteral("message"));
        return err;
    }
};

struct ClaudeResponse {
    std::optional<QString> id;
    std::optional<QVector<ClaudeContentBlock>> content;
    std::optional<ClaudeUsage> usage;
    std::optional<ClaudeError> error;

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalString(obj, QStringLiteral("id"), id);
        if (content.has_value()) {
            QJsonArray arr;
            for (const auto& c : content.value()) arr.append(c.toJson());
            obj[QStringLiteral("content")] = arr;
        }
        if (usage.has_value())
            obj[QStringLiteral("usage")] = usage->toJson();
        if (error.has_value())
            obj[QStringLiteral("error")] = error->toJson();
        return obj;
    }

    static ClaudeResponse fromJson(const QJsonObject& obj) {
        ClaudeResponse resp;
        resp.id = JsonHelper::readOptionalString(obj, QStringLiteral("id"));
        if (obj.contains(QStringLiteral("content")) && obj[QStringLiteral("content")].isArray()) {
            QVector<ClaudeContentBlock> vec;
            for (const auto& v : obj[QStringLiteral("content")].toArray())
                vec.append(ClaudeContentBlock::fromJson(v.toObject()));
            resp.content = vec;
        }
        if (obj.contains(QStringLiteral("usage")) && obj[QStringLiteral("usage")].isObject())
            resp.usage = ClaudeUsage::fromJson(obj[QStringLiteral("usage")].toObject());
        if (obj.contains(QStringLiteral("error")) && obj[QStringLiteral("error")].isObject())
            resp.error = ClaudeError::fromJson(obj[QStringLiteral("error")].toObject());
        return resp;
    }
};

// ============================================================================
// Anthropic Claude Streaming Models
// ============================================================================

struct ClaudeStreamDelta {
    std::optional<QString> type;
    std::optional<QString> text;
    std::optional<QString> stop_reason;

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalString(obj, QStringLiteral("type"), type);
        JsonHelper::writeOptionalString(obj, QStringLiteral("text"), text);
        JsonHelper::writeOptionalString(obj, QStringLiteral("stop_reason"), stop_reason);
        return obj;
    }

    static ClaudeStreamDelta fromJson(const QJsonObject& obj) {
        ClaudeStreamDelta d;
        d.type = JsonHelper::readOptionalString(obj, QStringLiteral("type"));
        d.text = JsonHelper::readOptionalString(obj, QStringLiteral("text"));
        d.stop_reason = JsonHelper::readOptionalString(obj, QStringLiteral("stop_reason"));
        return d;
    }
};

struct ClaudeStreamContentBlock {
    std::optional<QString> type;
    std::optional<QString> text;

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalString(obj, QStringLiteral("type"), type);
        JsonHelper::writeOptionalString(obj, QStringLiteral("text"), text);
        return obj;
    }

    static ClaudeStreamContentBlock fromJson(const QJsonObject& obj) {
        ClaudeStreamContentBlock block;
        block.type = JsonHelper::readOptionalString(obj, QStringLiteral("type"));
        block.text = JsonHelper::readOptionalString(obj, QStringLiteral("text"));
        return block;
    }
};

struct ClaudeStreamEvent {
    QString type; // message_start, content_block_start, content_block_delta, etc.
    std::optional<int> index;
    std::optional<ClaudeStreamDelta> delta;
    std::optional<ClaudeStreamContentBlock> content_block;

    QJsonObject toJson() const {
        QJsonObject obj;
        obj[QStringLiteral("type")] = type;
        JsonHelper::writeOptionalInt(obj, QStringLiteral("index"), index);
        if (delta.has_value())
            obj[QStringLiteral("delta")] = delta->toJson();
        if (content_block.has_value())
            obj[QStringLiteral("content_block")] = content_block->toJson();
        return obj;
    }

    static ClaudeStreamEvent fromJson(const QJsonObject& obj) {
        ClaudeStreamEvent evt;
        evt.type = obj[QStringLiteral("type")].toString();
        evt.index = JsonHelper::readOptionalInt(obj, QStringLiteral("index"));
        if (obj.contains(QStringLiteral("delta")) && obj[QStringLiteral("delta")].isObject())
            evt.delta = ClaudeStreamDelta::fromJson(obj[QStringLiteral("delta")].toObject());
        if (obj.contains(QStringLiteral("content_block")) && obj[QStringLiteral("content_block")].isObject())
            evt.content_block = ClaudeStreamContentBlock::fromJson(obj[QStringLiteral("content_block")].toObject());
        return evt;
    }
};

// ============================================================================
// Google Gemini Models
// ============================================================================

struct GeminiPart {
    QString text;

    QJsonObject toJson() const {
        QJsonObject obj;
        obj[QStringLiteral("text")] = text;
        return obj;
    }

    static GeminiPart fromJson(const QJsonObject& obj) {
        GeminiPart part;
        part.text = obj[QStringLiteral("text")].toString();
        return part;
    }
};

struct GeminiContent {
    QVector<GeminiPart> parts;
    std::optional<QString> role; // "user" or "model" for multi-turn chat

    QJsonObject toJson() const {
        QJsonObject obj;
        QJsonArray partsArr;
        for (const auto& p : parts) partsArr.append(p.toJson());
        obj[QStringLiteral("parts")] = partsArr;
        JsonHelper::writeOptionalString(obj, QStringLiteral("role"), role);
        return obj;
    }

    static GeminiContent fromJson(const QJsonObject& obj) {
        GeminiContent content;
        if (obj.contains(QStringLiteral("parts")) && obj[QStringLiteral("parts")].isArray()) {
            for (const auto& v : obj[QStringLiteral("parts")].toArray())
                content.parts.append(GeminiPart::fromJson(v.toObject()));
        }
        content.role = JsonHelper::readOptionalString(obj, QStringLiteral("role"));
        return content;
    }
};

struct GeminiGenerationConfig {
    std::optional<float> temperature;
    std::optional<float> topP;
    std::optional<int> topK;
    std::optional<int> maxOutputTokens;
    std::optional<QVector<QString>> stopSequences;
    std::optional<float> frequencyPenalty;
    std::optional<float> presencePenalty;
    std::optional<int> seed;

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("temperature"), temperature);
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("topP"), topP);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("topK"), topK);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("maxOutputTokens"), maxOutputTokens);
        JsonHelper::writeOptionalStringList(obj, QStringLiteral("stopSequences"), stopSequences);
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("frequencyPenalty"), frequencyPenalty);
        JsonHelper::writeOptionalFloat(obj, QStringLiteral("presencePenalty"), presencePenalty);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("seed"), seed);
        return obj;
    }

    static GeminiGenerationConfig fromJson(const QJsonObject& obj) {
        GeminiGenerationConfig cfg;
        cfg.temperature = JsonHelper::readOptionalFloat(obj, QStringLiteral("temperature"));
        cfg.topP = JsonHelper::readOptionalFloat(obj, QStringLiteral("topP"));
        cfg.topK = JsonHelper::readOptionalInt(obj, QStringLiteral("topK"));
        cfg.maxOutputTokens = JsonHelper::readOptionalInt(obj, QStringLiteral("maxOutputTokens"));
        cfg.stopSequences = JsonHelper::readOptionalStringList(obj, QStringLiteral("stopSequences"));
        cfg.frequencyPenalty = JsonHelper::readOptionalFloat(obj, QStringLiteral("frequencyPenalty"));
        cfg.presencePenalty = JsonHelper::readOptionalFloat(obj, QStringLiteral("presencePenalty"));
        cfg.seed = JsonHelper::readOptionalInt(obj, QStringLiteral("seed"));
        return cfg;
    }
};

struct GeminiRequest {
    QVector<GeminiContent> contents;
    std::optional<GeminiGenerationConfig> generationConfig;
    std::optional<GeminiContent> systemInstruction;

    QJsonObject toJson() const {
        QJsonObject obj;
        QJsonArray contentsArr;
        for (const auto& c : contents) contentsArr.append(c.toJson());
        obj[QStringLiteral("contents")] = contentsArr;
        if (generationConfig.has_value())
            obj[QStringLiteral("generationConfig")] = generationConfig->toJson();
        if (systemInstruction.has_value())
            obj[QStringLiteral("systemInstruction")] = systemInstruction->toJson();
        return obj;
    }

    static GeminiRequest fromJson(const QJsonObject& obj) {
        GeminiRequest req;
        if (obj.contains(QStringLiteral("contents")) && obj[QStringLiteral("contents")].isArray()) {
            for (const auto& v : obj[QStringLiteral("contents")].toArray())
                req.contents.append(GeminiContent::fromJson(v.toObject()));
        }
        if (obj.contains(QStringLiteral("generationConfig")) && obj[QStringLiteral("generationConfig")].isObject())
            req.generationConfig = GeminiGenerationConfig::fromJson(obj[QStringLiteral("generationConfig")].toObject());
        if (obj.contains(QStringLiteral("systemInstruction")) && obj[QStringLiteral("systemInstruction")].isObject())
            req.systemInstruction = GeminiContent::fromJson(obj[QStringLiteral("systemInstruction")].toObject());
        return req;
    }
};

struct GeminiCandidate {
    std::optional<GeminiContent> content;

    QJsonObject toJson() const {
        QJsonObject obj;
        if (content.has_value())
            obj[QStringLiteral("content")] = content->toJson();
        return obj;
    }

    static GeminiCandidate fromJson(const QJsonObject& obj) {
        GeminiCandidate cand;
        if (obj.contains(QStringLiteral("content")) && obj[QStringLiteral("content")].isObject())
            cand.content = GeminiContent::fromJson(obj[QStringLiteral("content")].toObject());
        return cand;
    }
};

struct GeminiUsageMetadata {
    std::optional<int> promptTokenCount;
    std::optional<int> candidatesTokenCount;
    std::optional<int> totalTokenCount;
    std::optional<double> cost;
    std::optional<qint64> cost_in_usd_ticks;
    std::optional<UsageCost> cost_usd;

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalInt(obj, QStringLiteral("promptTokenCount"), promptTokenCount);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("candidatesTokenCount"), candidatesTokenCount);
        JsonHelper::writeOptionalInt(obj, QStringLiteral("totalTokenCount"), totalTokenCount);
        JsonHelper::writeOptionalDouble(obj, QStringLiteral("cost"), cost);
        JsonHelper::writeOptionalInt64(obj, QStringLiteral("cost_in_usd_ticks"), cost_in_usd_ticks);
        if (cost_usd.has_value())
            obj[QStringLiteral("cost_usd")] = cost_usd->toJson();
        return obj;
    }

    static GeminiUsageMetadata fromJson(const QJsonObject& obj) {
        GeminiUsageMetadata meta;
        meta.promptTokenCount = JsonHelper::readOptionalInt(obj, QStringLiteral("promptTokenCount"));
        meta.candidatesTokenCount = JsonHelper::readOptionalInt(obj, QStringLiteral("candidatesTokenCount"));
        meta.totalTokenCount = JsonHelper::readOptionalInt(obj, QStringLiteral("totalTokenCount"));
        meta.cost = JsonHelper::readOptionalDouble(obj, QStringLiteral("cost"));
        meta.cost_in_usd_ticks = JsonHelper::readOptionalInt64(obj, QStringLiteral("cost_in_usd_ticks"));
        if (obj.contains(QStringLiteral("cost_usd")) && obj[QStringLiteral("cost_usd")].isObject())
            meta.cost_usd = UsageCost::fromJson(obj[QStringLiteral("cost_usd")].toObject());
        return meta;
    }
};

struct GeminiError {
    std::optional<int> code;
    std::optional<QString> message;
    std::optional<QString> status;

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalInt(obj, QStringLiteral("code"), code);
        JsonHelper::writeOptionalString(obj, QStringLiteral("message"), message);
        JsonHelper::writeOptionalString(obj, QStringLiteral("status"), status);
        return obj;
    }

    static GeminiError fromJson(const QJsonObject& obj) {
        GeminiError err;
        err.code = JsonHelper::readOptionalInt(obj, QStringLiteral("code"));
        err.message = JsonHelper::readOptionalString(obj, QStringLiteral("message"));
        err.status = JsonHelper::readOptionalString(obj, QStringLiteral("status"));
        return err;
    }
};

struct GeminiResponse {
    std::optional<QVector<GeminiCandidate>> candidates;
    std::optional<GeminiUsageMetadata> usageMetadata;
    std::optional<GeminiError> error;

    QJsonObject toJson() const {
        QJsonObject obj;
        if (candidates.has_value()) {
            QJsonArray arr;
            for (const auto& c : candidates.value()) arr.append(c.toJson());
            obj[QStringLiteral("candidates")] = arr;
        }
        if (usageMetadata.has_value())
            obj[QStringLiteral("usageMetadata")] = usageMetadata->toJson();
        if (error.has_value())
            obj[QStringLiteral("error")] = error->toJson();
        return obj;
    }

    static GeminiResponse fromJson(const QJsonObject& obj) {
        GeminiResponse resp;
        if (obj.contains(QStringLiteral("candidates")) && obj[QStringLiteral("candidates")].isArray()) {
            QVector<GeminiCandidate> vec;
            for (const auto& v : obj[QStringLiteral("candidates")].toArray())
                vec.append(GeminiCandidate::fromJson(v.toObject()));
            resp.candidates = vec;
        }
        if (obj.contains(QStringLiteral("usageMetadata")) && obj[QStringLiteral("usageMetadata")].isObject())
            resp.usageMetadata = GeminiUsageMetadata::fromJson(obj[QStringLiteral("usageMetadata")].toObject());
        if (obj.contains(QStringLiteral("error")) && obj[QStringLiteral("error")].isObject())
            resp.error = GeminiError::fromJson(obj[QStringLiteral("error")].toObject());
        return resp;
    }
};

// ============================================================================
// Google Gemini Streaming Models
// ============================================================================

struct GeminiStreamCandidate {
    std::optional<GeminiContent> content;
    std::optional<QString> finishReason;

    QJsonObject toJson() const {
        QJsonObject obj;
        if (content.has_value())
            obj[QStringLiteral("content")] = content->toJson();
        JsonHelper::writeOptionalString(obj, QStringLiteral("finishReason"), finishReason);
        return obj;
    }

    static GeminiStreamCandidate fromJson(const QJsonObject& obj) {
        GeminiStreamCandidate cand;
        if (obj.contains(QStringLiteral("content")) && obj[QStringLiteral("content")].isObject())
            cand.content = GeminiContent::fromJson(obj[QStringLiteral("content")].toObject());
        cand.finishReason = JsonHelper::readOptionalString(obj, QStringLiteral("finishReason"));
        return cand;
    }
};

struct GeminiStreamChunk {
    std::optional<QVector<GeminiStreamCandidate>> candidates;

    QJsonObject toJson() const {
        QJsonObject obj;
        if (candidates.has_value()) {
            QJsonArray arr;
            for (const auto& c : candidates.value()) arr.append(c.toJson());
            obj[QStringLiteral("candidates")] = arr;
        }
        return obj;
    }

    static GeminiStreamChunk fromJson(const QJsonObject& obj) {
        GeminiStreamChunk chunk;
        if (obj.contains(QStringLiteral("candidates")) && obj[QStringLiteral("candidates")].isArray()) {
            QVector<GeminiStreamCandidate> vec;
            for (const auto& v : obj[QStringLiteral("candidates")].toArray())
                vec.append(GeminiStreamCandidate::fromJson(v.toObject()));
            chunk.candidates = vec;
        }
        return chunk;
    }
};

// ============================================================================
// Model List Responses
// ============================================================================

struct OpenAiModel {
    std::optional<QString> id;
    std::optional<QString> owned_by;

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalString(obj, QStringLiteral("id"), id);
        JsonHelper::writeOptionalString(obj, QStringLiteral("owned_by"), owned_by);
        return obj;
    }

    static OpenAiModel fromJson(const QJsonObject& obj) {
        OpenAiModel model;
        model.id = JsonHelper::readOptionalString(obj, QStringLiteral("id"));
        model.owned_by = JsonHelper::readOptionalString(obj, QStringLiteral("owned_by"));
        return model;
    }
};

struct OpenAiModelsResponse {
    std::optional<QVector<OpenAiModel>> data;

    QJsonObject toJson() const {
        QJsonObject obj;
        if (data.has_value()) {
            QJsonArray arr;
            for (const auto& m : data.value()) arr.append(m.toJson());
            obj[QStringLiteral("data")] = arr;
        }
        return obj;
    }

    static OpenAiModelsResponse fromJson(const QJsonObject& obj) {
        OpenAiModelsResponse resp;
        if (obj.contains(QStringLiteral("data")) && obj[QStringLiteral("data")].isArray()) {
            QVector<OpenAiModel> vec;
            for (const auto& v : obj[QStringLiteral("data")].toArray())
                vec.append(OpenAiModel::fromJson(v.toObject()));
            resp.data = vec;
        }
        return resp;
    }
};

struct ClaudeModelInfo {
    std::optional<QString> id;
    std::optional<QString> display_name;
    std::optional<QString> type;

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalString(obj, QStringLiteral("id"), id);
        JsonHelper::writeOptionalString(obj, QStringLiteral("display_name"), display_name);
        JsonHelper::writeOptionalString(obj, QStringLiteral("type"), type);
        return obj;
    }

    static ClaudeModelInfo fromJson(const QJsonObject& obj) {
        ClaudeModelInfo info;
        info.id = JsonHelper::readOptionalString(obj, QStringLiteral("id"));
        info.display_name = JsonHelper::readOptionalString(obj, QStringLiteral("display_name"));
        info.type = JsonHelper::readOptionalString(obj, QStringLiteral("type"));
        return info;
    }
};

struct ClaudeModelsResponse {
    std::optional<QVector<ClaudeModelInfo>> data;

    QJsonObject toJson() const {
        QJsonObject obj;
        if (data.has_value()) {
            QJsonArray arr;
            for (const auto& m : data.value()) arr.append(m.toJson());
            obj[QStringLiteral("data")] = arr;
        }
        return obj;
    }

    static ClaudeModelsResponse fromJson(const QJsonObject& obj) {
        ClaudeModelsResponse resp;
        if (obj.contains(QStringLiteral("data")) && obj[QStringLiteral("data")].isArray()) {
            QVector<ClaudeModelInfo> vec;
            for (const auto& v : obj[QStringLiteral("data")].toArray())
                vec.append(ClaudeModelInfo::fromJson(v.toObject()));
            resp.data = vec;
        }
        return resp;
    }
};

struct GeminiModel {
    std::optional<QString> name;
    std::optional<QString> displayName;
    std::optional<QVector<QString>> supportedGenerationMethods;

    QJsonObject toJson() const {
        QJsonObject obj;
        JsonHelper::writeOptionalString(obj, QStringLiteral("name"), name);
        JsonHelper::writeOptionalString(obj, QStringLiteral("displayName"), displayName);
        JsonHelper::writeOptionalStringList(obj, QStringLiteral("supportedGenerationMethods"), supportedGenerationMethods);
        return obj;
    }

    static GeminiModel fromJson(const QJsonObject& obj) {
        GeminiModel model;
        model.name = JsonHelper::readOptionalString(obj, QStringLiteral("name"));
        model.displayName = JsonHelper::readOptionalString(obj, QStringLiteral("displayName"));
        model.supportedGenerationMethods = JsonHelper::readOptionalStringList(obj, QStringLiteral("supportedGenerationMethods"));
        return model;
    }
};

struct GeminiModelsResponse {
    std::optional<QVector<GeminiModel>> models;

    QJsonObject toJson() const {
        QJsonObject obj;
        if (models.has_value()) {
            QJsonArray arr;
            for (const auto& m : models.value()) arr.append(m.toJson());
            obj[QStringLiteral("models")] = arr;
        }
        return obj;
    }

    static GeminiModelsResponse fromJson(const QJsonObject& obj) {
        GeminiModelsResponse resp;
        if (obj.contains(QStringLiteral("models")) && obj[QStringLiteral("models")].isArray()) {
            QVector<GeminiModel> vec;
            for (const auto& v : obj[QStringLiteral("models")].toArray())
                vec.append(GeminiModel::fromJson(v.toObject()));
            resp.models = vec;
        }
        return resp;
    }
};
