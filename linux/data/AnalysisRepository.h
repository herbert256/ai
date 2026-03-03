#pragma once
#include <QObject>
#include <QString>
#include <QJsonObject>
#include <optional>
#include "DataModels.h"
#include "ApiModels.h"
#include "AppService.h"

class AnalysisRepository : public QObject {
    Q_OBJECT
public:
    static AnalysisRepository& instance();

    static const QString testPrompt; // "Reply with exactly: OK"
    static constexpr double xaiCostTicksDivisor = 10000000000.0;

    // Cost extraction
    static std::optional<double> extractOpenAiCost(const QJsonObject &usage, const AppService *provider = nullptr);
    static std::optional<double> extractClaudeCost(const QJsonObject &usage);
    static std::optional<double> extractGeminiCost(const QJsonObject &usage);

    // Prompt building
    QString formatCurrentDate() const;
    QString buildPrompt(const QString &tmpl, const QString &content,
                        const QString &model = "", const QString &providerName = "",
                        const QString &agentName = "") const;

    // Parameter merging: override non-null values win, booleans are "sticky true" (OR)
    static AgentParameters mergeParameters(const AgentParameters &base, const AgentParameters &override);

    // Validate parameter ranges
    static AgentParameters validateParams(const AgentParameters &params);

    // Check if model uses Responses API endpoint
    static bool usesResponsesApi(const AppService &service, const QString &model);

    // Format utilities
    static QString formatUsageJson(const QJsonObject &usage);
    static QString formatHeaders(const QList<QPair<QString, QString>> &headers);

    // Token estimation
    static int estimateTokens(const QString &text);

private:
    explicit AnalysisRepository(QObject *parent = nullptr);
};
