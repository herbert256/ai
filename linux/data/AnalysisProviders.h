// AnalysisProviders.h - Non-streaming analysis dispatch for reports (Linux/Qt6 port)
// Dispatches to format-specific implementations: OpenAI-compatible, Anthropic Claude, Google Gemini.

#pragma once
#include <QObject>
#include <QJsonArray>
#include <functional>
#include <optional>
#include "DataModels.h"
#include "AppService.h"

class AnalysisProviders : public QObject {
    Q_OBJECT
public:
    static AnalysisProviders& instance();

    // Main entry point - dispatches by provider's apiFormat
    void analyzeWithAgent(const QString &serviceId, const QString &apiKey,
                          const QString &model, const QString &prompt,
                          const AgentParameters &params,
                          const QString &agentName,
                          const QString &customBaseUrl,
                          std::function<void(AnalysisResponse)> callback);

private:
    explicit AnalysisProviders(QObject *parent = nullptr);

    // Format-specific implementations
    void analyzeOpenAiCompatible(const AppService &service, const QString &apiKey,
                                  const QString &prompt, const QString &model,
                                  const AgentParameters &params,
                                  const QString &customBaseUrl,
                                  std::function<void(AnalysisResponse)> callback);

    void analyzeResponsesApi(const AppService &service, const QString &apiKey,
                              const QString &prompt, const QString &model,
                              const AgentParameters &params,
                              const QString &customBaseUrl,
                              std::function<void(AnalysisResponse)> callback);

    void analyzeClaude(const AppService &service, const QString &apiKey,
                        const QString &prompt, const QString &model,
                        const AgentParameters &params,
                        std::function<void(AnalysisResponse)> callback);

    void analyzeGemini(const AppService &service, const QString &apiKey,
                        const QString &prompt, const QString &model,
                        const AgentParameters &params,
                        std::function<void(AnalysisResponse)> callback);

    QJsonArray buildOpenAiMessages(const QString &prompt, const std::optional<QString> &systemPrompt);
};
