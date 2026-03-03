using System.Text.Json;
using AI.ViewModels;

namespace AI.Data;

public static class AnalysisProviders
{
    private static readonly AnalysisRepository Repo = AnalysisRepository.Instance;

    public static async Task<AnalysisResponse> AnalyzeWithAgent(
        ViewModels.Agent agent, string content, string prompt,
        ViewModels.Settings settings, AgentParameters? overrideParams = null)
    {
        var provider = agent.Provider;
        if (provider == null)
            return new AnalysisResponse(AppService.Entries.First(), error: $"Provider not found for agent {agent.Name}");

        var effectiveApiKey = settings.GetEffectiveApiKeyForAgent(agent);
        if (string.IsNullOrEmpty(effectiveApiKey))
            return new AnalysisResponse(provider, error: $"API key not configured for agent {agent.Name}", agentName: agent.Name);

        var finalPrompt = Repo.BuildPrompt(prompt, content, agent);
        var agentResolvedParams = settings.ResolveAgentParameters(agent);
        var mergedParams = Repo.ValidateParams(Repo.MergeParameters(agentResolvedParams, overrideParams));
        var customBaseUrl = settings.GetEffectiveEndpointUrlForAgent(agent);
        var effectiveModel = settings.GetEffectiveModelForAgent(agent);

        return await Repo.WithRetry(
            $"Agent {agent.Name}",
            async () =>
            {
                var result = provider.ApiFormat switch
                {
                    ApiFormat.Anthropic => await AnalyzeWithClaude(provider, effectiveApiKey, finalPrompt, effectiveModel, mergedParams),
                    ApiFormat.Google => await AnalyzeWithGemini(provider, effectiveApiKey, finalPrompt, effectiveModel, mergedParams),
                    _ => await AnalyzeWithOpenAiCompatible(provider, effectiveApiKey, finalPrompt, effectiveModel, mergedParams, customBaseUrl)
                };
                result.AgentName = agent.Name;
                result.PromptUsed = finalPrompt;
                return result;
            },
            r => r.IsSuccess,
            ex => new AnalysisResponse(provider, error: $"Network error after retry: {ex.Message}", agentName: agent.Name)
        );
    }

    public static async Task<AnalysisResponse> AnalyzeWithOpenAiCompatible(
        AppService service, string apiKey, string prompt, string model,
        AgentParameters parms, string? customBaseUrl = null)
    {
        if (Repo.UsesResponsesApi(service, model))
            return await AnalyzeWithResponsesApi(service, apiKey, prompt, model, parms, customBaseUrl);

        var messages = BuildOpenAiMessages(prompt, parms.SystemPrompt);
        var request = new OpenAiRequest
        {
            Model = model,
            Messages = messages,
            MaxTokens = parms.MaxTokens,
            Temperature = parms.Temperature,
            TopP = parms.TopP,
            TopK = parms.TopK,
            FrequencyPenalty = parms.FrequencyPenalty,
            PresencePenalty = parms.PresencePenalty,
            Stop = parms.StopSequences,
            Seed = service.SeedFieldName == "seed" ? parms.Seed : null,
            RandomSeed = service.SeedFieldName == "random_seed" ? parms.Seed : null,
            ResponseFormat = parms.ResponseFormatJson ? new OpenAiResponseFormat { Type = "json_object" } : null,
            ReturnCitations = service.SupportsCitations ? parms.ReturnCitations : null,
            SearchRecencyFilter = service.SupportsSearchRecency ? parms.SearchRecency : null,
            Search = parms.SearchEnabled ? true : null
        };

        var url = ApiClient.ChatUrl(service, customBaseUrl);
        try
        {
            var (response, statusCode) = await ApiClient.Instance.RequestAsync<OpenAiRequest, OpenAiResponse>(
                url, request, service, apiKey);

            if (response.Error != null)
                return new AnalysisResponse(service, error: response.Error.Message ?? "Unknown error", httpStatusCode: statusCode);

            var content = response.Choices?.FirstOrDefault()?.Message?.Content
                ?? response.Choices?.FirstOrDefault()?.Message?.ReasoningContent;

            var usage = response.Usage;
            var tokenUsage = new TokenUsage(
                usage?.EffectiveInputTokens ?? 0,
                usage?.EffectiveOutputTokens ?? 0,
                AnalysisRepository.ExtractApiCost(usage, service));

            return new AnalysisResponse(service, analysis: content,
                error: content == null ? "No content in response" : null,
                tokenUsage: tokenUsage, httpStatusCode: statusCode)
            {
                Citations = response.Citations,
                SearchResults = response.SearchResults,
                RelatedQuestions = response.RelatedQuestions,
                RawUsageJson = Repo.FormatUsageJson(usage)
            };
        }
        catch (Exception ex)
        {
            return new AnalysisResponse(service, error: ex.Message);
        }
    }

    private static async Task<AnalysisResponse> AnalyzeWithResponsesApi(
        AppService service, string apiKey, string prompt, string model,
        AgentParameters parms, string? customBaseUrl = null)
    {
        var request = OpenAiResponsesRequest.FromString(model, prompt, parms.SystemPrompt);
        var baseUrl = customBaseUrl ?? service.BaseUrl;
        var normalizedBase = baseUrl.EndsWith("/") ? baseUrl : $"{baseUrl}/";
        var url = $"{normalizedBase}v1/responses";

        try
        {
            var (response, statusCode) = await ApiClient.Instance.RequestAsync<OpenAiResponsesRequest, OpenAiResponsesApiResponse>(
                url, request, service, apiKey);

            if (response.Error != null)
                return new AnalysisResponse(service, error: response.Error.Message ?? "Unknown error", httpStatusCode: statusCode);

            var content = response.Output?
                .Select(msg => msg.Content?.FirstOrDefault(c => c.Type == "output_text")?.Text ?? msg.Content?.FirstOrDefault()?.Text)
                .FirstOrDefault(t => t != null);

            var usage = response.Usage;
            var tokenUsage = new TokenUsage(
                usage?.EffectiveInputTokens ?? 0,
                usage?.EffectiveOutputTokens ?? 0,
                AnalysisRepository.ExtractApiCost(usage, service));

            return new AnalysisResponse(service, analysis: content,
                error: content == null ? "No content in response" : null,
                tokenUsage: tokenUsage, httpStatusCode: statusCode)
            {
                RawUsageJson = Repo.FormatUsageJson(usage)
            };
        }
        catch (Exception ex)
        {
            return new AnalysisResponse(service, error: ex.Message);
        }
    }

    public static async Task<AnalysisResponse> AnalyzeWithClaude(
        AppService service, string apiKey, string prompt, string model, AgentParameters parms)
    {
        var request = new ClaudeRequest
        {
            Model = model,
            MaxTokens = parms.MaxTokens ?? 4096,
            Messages = new List<ClaudeMessage> { new() { Role = "user", Content = prompt } },
            Temperature = parms.Temperature,
            TopP = parms.TopP,
            TopK = parms.TopK,
            System = parms.SystemPrompt,
            StopSequences = parms.StopSequences,
            Search = parms.SearchEnabled ? true : null
        };

        var baseUrl = service.BaseUrl.EndsWith("/") ? service.BaseUrl : $"{service.BaseUrl}/";
        var url = $"{baseUrl}v1/messages";

        try
        {
            var (response, statusCode) = await ApiClient.Instance.RequestAsync<ClaudeRequest, ClaudeResponse>(
                url, request, service, apiKey);

            if (response.Error != null)
                return new AnalysisResponse(service, error: response.Error.Message ?? "Unknown error", httpStatusCode: statusCode);

            var content = response.Content?.FirstOrDefault(c => c.Type == "text")?.Text;
            var tokenUsage = new TokenUsage(
                response.Usage?.InputTokens ?? 0,
                response.Usage?.OutputTokens ?? 0,
                AnalysisRepository.ExtractApiCost(response.Usage));

            return new AnalysisResponse(service, analysis: content,
                error: content == null ? "No content in response" : null,
                tokenUsage: tokenUsage, httpStatusCode: statusCode)
            {
                RawUsageJson = Repo.FormatUsageJson(response.Usage)
            };
        }
        catch (Exception ex)
        {
            return new AnalysisResponse(service, error: ex.Message);
        }
    }

    public static async Task<AnalysisResponse> AnalyzeWithGemini(
        AppService service, string apiKey, string prompt, string model, AgentParameters parms)
    {
        var config = new GeminiGenerationConfig
        {
            Temperature = parms.Temperature,
            TopP = parms.TopP,
            TopK = parms.TopK,
            MaxOutputTokens = parms.MaxTokens,
            StopSequences = parms.StopSequences
        };

        var systemInstruction = parms.SystemPrompt != null
            ? new GeminiContent { Parts = new() { new GeminiPart { Text = parms.SystemPrompt } } }
            : null;

        var request = new GeminiRequest
        {
            Contents = new() { new GeminiContent { Parts = new() { new GeminiPart { Text = prompt } }, Role = "user" } },
            GenerationConfig = config,
            SystemInstruction = systemInstruction
        };

        var url = ApiClient.GeminiGenerateUrl(service.BaseUrl, model, apiKey);

        try
        {
            var dummyService = new AppService { Id = "gemini_temp", ApiFormatStr = "GOOGLE" };
            var (response, statusCode) = await ApiClient.Instance.RequestAsync<GeminiRequest, GeminiResponse>(
                url, request, dummyService, apiKey);

            if (response.Error != null)
                return new AnalysisResponse(service, error: response.Error.Message ?? "Unknown error", httpStatusCode: statusCode);

            var content = response.Candidates?.FirstOrDefault()?.Content?.Parts?.FirstOrDefault()?.Text;
            var usage = response.UsageMetadata;
            var tokenUsage = new TokenUsage(
                usage?.PromptTokenCount ?? 0,
                usage?.CandidatesTokenCount ?? 0,
                AnalysisRepository.ExtractApiCost(usage));

            return new AnalysisResponse(service, analysis: content,
                error: content == null ? "No content in response" : null,
                tokenUsage: tokenUsage, httpStatusCode: statusCode)
            {
                RawUsageJson = Repo.FormatUsageJson(usage)
            };
        }
        catch (Exception ex)
        {
            return new AnalysisResponse(service, error: ex.Message);
        }
    }

    private static List<OpenAiMessage> BuildOpenAiMessages(string prompt, string? systemPrompt)
    {
        var messages = new List<OpenAiMessage>();
        if (!string.IsNullOrEmpty(systemPrompt))
            messages.Add(new OpenAiMessage { Role = "system", Content = systemPrompt });
        messages.Add(new OpenAiMessage { Role = "user", Content = prompt });
        return messages;
    }
}
