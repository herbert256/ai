#pragma once
#include <QObject>
#include <functional>
#include "AppService.h"

class AnalysisModels : public QObject {
    Q_OBJECT
public:
    static AnalysisModels& instance();

    // Fetch available models for a provider
    void fetchModels(const AppService &service, const QString &apiKey,
                     const QString &customUrl,
                     std::function<void(QStringList models)> callback);

    // Test API connection with simple prompt "Reply with exactly: OK"
    void testModel(const AppService &service, const QString &apiKey,
                   const QString &model,
                   std::function<void(QString error)> callback); // empty string = success

    // Test with custom prompt
    void testModelWithPrompt(const AppService &service, const QString &apiKey,
                              const QString &model, const QString &prompt,
                              std::function<void(QString response, QString error)> callback);

private:
    explicit AnalysisModels(QObject *parent = nullptr);

    void fetchOpenAiCompatibleModels(const AppService &service, const QString &apiKey,
                                      const QString &customUrl,
                                      std::function<void(QStringList)> callback);
    void fetchClaudeModels(const AppService &service, const QString &apiKey,
                            std::function<void(QStringList)> callback);
    void fetchGeminiModels(const AppService &service, const QString &apiKey,
                            std::function<void(QStringList)> callback);

    QStringList filterModels(const QStringList &models, const AppService &service);
};
