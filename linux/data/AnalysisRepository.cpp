#include "AnalysisRepository.h"
#include <QDate>
#include <QLocale>
#include <QJsonDocument>
#include <QJsonValue>
#include <QtMath>
#include <algorithm>

// ---------------------------------------------------------------------------
// Static members
// ---------------------------------------------------------------------------

const QString AnalysisRepository::testPrompt = QStringLiteral("Reply with exactly: OK");

// ---------------------------------------------------------------------------
// Singleton
// ---------------------------------------------------------------------------

AnalysisRepository& AnalysisRepository::instance() {
    static AnalysisRepository repo;
    return repo;
}

AnalysisRepository::AnalysisRepository(QObject *parent)
    : QObject(parent)
{
}

// ---------------------------------------------------------------------------
// Cost extraction - OpenAI compatible
// ---------------------------------------------------------------------------

std::optional<double> AnalysisRepository::extractOpenAiCost(const QJsonObject &usage, const AppService *provider) {
    if (usage.isEmpty())
        return std::nullopt;

    // Provider with extractApiCost flag (e.g., OpenRouter): use cost from API response
    if (provider && provider->extractApiCost) {
        const QJsonValue costVal = usage[QStringLiteral("cost")];
        if (costVal.isDouble())
            return costVal.toDouble();
        // Try nested cost.total_cost
        if (costVal.isObject()) {
            const QJsonValue totalCost = costVal.toObject()[QStringLiteral("total_cost")];
            if (totalCost.isDouble())
                return totalCost.toDouble();
        }
    }

    // Check provider-specific cost ticks (e.g., xAI: cost in billionths of a dollar)
    if (provider && provider->costTicksDivisor.has_value()) {
        const QJsonValue ticksVal = usage[QStringLiteral("cost_in_usd_ticks")];
        if (!ticksVal.isUndefined() && !ticksVal.isNull()) {
            double ticks = ticksVal.toDouble();
            if (ticks != 0.0)
                return ticks / *provider->costTicksDivisor;
        }
        return std::nullopt;
    }

    // Fallback: check cost_in_usd_ticks with default divisor
    const QJsonValue ticksVal = usage[QStringLiteral("cost_in_usd_ticks")];
    if (!ticksVal.isUndefined() && !ticksVal.isNull()) {
        double ticks = ticksVal.toDouble();
        if (ticks != 0.0)
            return ticks / xaiCostTicksDivisor;
    }

    return std::nullopt;
}

// ---------------------------------------------------------------------------
// Cost extraction - Claude (Anthropic)
// ---------------------------------------------------------------------------

std::optional<double> AnalysisRepository::extractClaudeCost(const QJsonObject &usage) {
    if (usage.isEmpty())
        return std::nullopt;

    // Direct cost field
    const QJsonValue costVal = usage[QStringLiteral("cost")];
    if (costVal.isDouble())
        return costVal.toDouble();

    // Cost in USD ticks
    const QJsonValue ticksVal = usage[QStringLiteral("cost_in_usd_ticks")];
    if (!ticksVal.isUndefined() && !ticksVal.isNull()) {
        double ticks = ticksVal.toDouble();
        if (ticks != 0.0)
            return ticks / 10000000000.0;
    }

    // Nested cost_usd.total_cost
    const QJsonValue costUsd = usage[QStringLiteral("cost_usd")];
    if (costUsd.isObject()) {
        const QJsonValue totalCost = costUsd.toObject()[QStringLiteral("total_cost")];
        if (totalCost.isDouble())
            return totalCost.toDouble();
    }

    // Direct total_cost fallback
    const QJsonValue totalCost = usage[QStringLiteral("total_cost")];
    if (totalCost.isDouble())
        return totalCost.toDouble();

    return std::nullopt;
}

// ---------------------------------------------------------------------------
// Cost extraction - Gemini (Google)
// ---------------------------------------------------------------------------

std::optional<double> AnalysisRepository::extractGeminiCost(const QJsonObject &usage) {
    if (usage.isEmpty())
        return std::nullopt;

    // Direct cost field
    const QJsonValue costVal = usage[QStringLiteral("cost")];
    if (costVal.isDouble())
        return costVal.toDouble();

    // Cost in USD ticks
    const QJsonValue ticksVal = usage[QStringLiteral("cost_in_usd_ticks")];
    if (!ticksVal.isUndefined() && !ticksVal.isNull()) {
        double ticks = ticksVal.toDouble();
        if (ticks != 0.0)
            return ticks / 10000000000.0;
    }

    // Nested cost_usd.total_cost
    const QJsonValue costUsd = usage[QStringLiteral("cost_usd")];
    if (costUsd.isObject()) {
        const QJsonValue totalCost = costUsd.toObject()[QStringLiteral("total_cost")];
        if (totalCost.isDouble())
            return totalCost.toDouble();
    }

    // Direct total_cost fallback
    const QJsonValue totalCost = usage[QStringLiteral("total_cost")];
    if (totalCost.isDouble())
        return totalCost.toDouble();

    return std::nullopt;
}

// ---------------------------------------------------------------------------
// Prompt building
// ---------------------------------------------------------------------------

QString AnalysisRepository::formatCurrentDate() const {
    const QDate today = QDate::currentDate();
    const QLocale locale(QLocale::English);

    const QString dayOfWeek = locale.dayName(today.dayOfWeek(), QLocale::LongFormat);
    const QString month = locale.monthName(today.month(), QLocale::LongFormat);
    const int day = today.day();

    QString suffix;
    if (day >= 11 && day <= 13) {
        suffix = QStringLiteral("th");
    } else {
        switch (day % 10) {
        case 1:  suffix = QStringLiteral("st"); break;
        case 2:  suffix = QStringLiteral("nd"); break;
        case 3:  suffix = QStringLiteral("rd"); break;
        default: suffix = QStringLiteral("th"); break;
        }
    }

    return QStringLiteral("%1, %2 %3%4").arg(dayOfWeek, month).arg(day).arg(suffix);
}

QString AnalysisRepository::buildPrompt(const QString &tmpl, const QString &content,
                                         const QString &model, const QString &providerName,
                                         const QString &agentName) const {
    QString result = tmpl;
    result.replace(QStringLiteral("@FEN@"), content);
    result.replace(QStringLiteral("@DATE@"), formatCurrentDate());
    result.replace(QStringLiteral("@MODEL@"), model);
    result.replace(QStringLiteral("@PROVIDER@"), providerName);
    result.replace(QStringLiteral("@AGENT@"), agentName);
    result.replace(QStringLiteral("@NOW@"),
                   QDateTime::currentDateTime().toString(QStringLiteral("yyyy-MM-dd HH:mm")));
    return result;
}

// ---------------------------------------------------------------------------
// Parameter merging
// ---------------------------------------------------------------------------

AgentParameters AnalysisRepository::mergeParameters(const AgentParameters &base,
                                                     const AgentParameters &over) {
    AgentParameters result;

    // Numeric optionals: override wins if present, else keep base
    result.temperature      = over.temperature.has_value()      ? over.temperature      : base.temperature;
    result.maxTokens        = over.maxTokens.has_value()        ? over.maxTokens        : base.maxTokens;
    result.topP             = over.topP.has_value()             ? over.topP             : base.topP;
    result.topK             = over.topK.has_value()             ? over.topK             : base.topK;
    result.frequencyPenalty = over.frequencyPenalty.has_value()  ? over.frequencyPenalty  : base.frequencyPenalty;
    result.presencePenalty  = over.presencePenalty.has_value()   ? over.presencePenalty   : base.presencePenalty;
    result.seed             = over.seed.has_value()             ? over.seed             : base.seed;
    result.searchRecency    = over.searchRecency.has_value()    ? over.searchRecency    : base.searchRecency;

    // String optionals: override wins if non-empty
    if (over.systemPrompt.has_value() && !over.systemPrompt->isEmpty())
        result.systemPrompt = over.systemPrompt;
    else
        result.systemPrompt = base.systemPrompt;

    // List optionals: override wins if non-empty
    if (over.stopSequences.has_value() && !over.stopSequences->isEmpty())
        result.stopSequences = over.stopSequences;
    else
        result.stopSequences = base.stopSequences;

    // Booleans: sticky true (OR)
    result.responseFormatJson = base.responseFormatJson || over.responseFormatJson;
    result.searchEnabled      = base.searchEnabled      || over.searchEnabled;
    result.returnCitations    = base.returnCitations     || over.returnCitations;

    return result;
}

// ---------------------------------------------------------------------------
// Parameter validation
// ---------------------------------------------------------------------------

AgentParameters AnalysisRepository::validateParams(const AgentParameters &params) {
    AgentParameters p = params;

    if (p.temperature.has_value())
        p.temperature = std::clamp(*p.temperature, 0.0f, 2.0f);

    if (p.topP.has_value())
        p.topP = std::clamp(*p.topP, 0.0f, 1.0f);

    if (p.topK.has_value())
        p.topK = std::max(*p.topK, 1);

    if (p.maxTokens.has_value())
        p.maxTokens = std::max(*p.maxTokens, 1);

    if (p.frequencyPenalty.has_value())
        p.frequencyPenalty = std::clamp(*p.frequencyPenalty, -2.0f, 2.0f);

    if (p.presencePenalty.has_value())
        p.presencePenalty = std::clamp(*p.presencePenalty, -2.0f, 2.0f);

    return p;
}

// ---------------------------------------------------------------------------
// Responses API check
// ---------------------------------------------------------------------------

bool AnalysisRepository::usesResponsesApi(const AppService &service, const QString &model) {
    if (service.endpointRules.isEmpty())
        return false;

    const QString lowerModel = model.toLower();
    for (const auto &rule : service.endpointRules) {
        if (lowerModel.startsWith(rule.modelPrefix.toLower())
            && rule.endpointType == QStringLiteral("responses")) {
            return true;
        }
    }
    return false;
}

// ---------------------------------------------------------------------------
// Format utilities
// ---------------------------------------------------------------------------

QString AnalysisRepository::formatUsageJson(const QJsonObject &usage) {
    if (usage.isEmpty())
        return QString();

    const QJsonDocument doc(usage);
    return QString::fromUtf8(doc.toJson(QJsonDocument::Indented));
}

QString AnalysisRepository::formatHeaders(const QList<QPair<QString, QString>> &headers) {
    QStringList lines;
    lines.reserve(headers.size());
    for (const auto &pair : headers) {
        lines.append(QStringLiteral("%1: %2").arg(pair.first, pair.second));
    }
    return lines.join(QStringLiteral("\n"));
}

// ---------------------------------------------------------------------------
// Token estimation
// ---------------------------------------------------------------------------

int AnalysisRepository::estimateTokens(const QString &text) {
    return qMax(1, text.length() / 4);
}
