namespace AI.Data;

public static class AnalysisChat
{
    private static readonly AnalysisRepository Repo = AnalysisRepository.Instance;

    public static async Task<string> SendChatMessage(
        AppService service, string apiKey, string model, List<ChatMessage> messages,
        ChatParameters parms, string? customBaseUrl = null)
    {
        return service.ApiFormat switch
        {
            ApiFormat.Anthropic => await SendChatMessageClaude(service, apiKey, model, messages, parms),
            ApiFormat.Google => await SendChatMessageGemini(service, apiKey, model, messages, parms),
            _ => await SendChatMessageOpenAiCompatible(service, apiKey, model, messages, parms, customBaseUrl)
        };
    }

    private static async Task<string> SendChatMessageOpenAiCompatible(
        AppService service, string apiKey, string model, List<ChatMessage> messages,
        ChatParameters parms, string? customBaseUrl)
    {
        if (Repo.UsesResponsesApi(service, model))
            return await SendChatMessageResponsesApi(service, apiKey, model, messages, parms, customBaseUrl);

        var openAiMessages = Repo.ConvertToOpenAiMessages(messages, parms.SystemPrompt);
        var request = new OpenAiRequest
        {
            Model = model,
            Messages = openAiMessages,
            MaxTokens = parms.MaxTokens,
            Temperature = parms.Temperature,
            TopP = parms.TopP,
            TopK = parms.TopK,
            FrequencyPenalty = parms.FrequencyPenalty,
            PresencePenalty = parms.PresencePenalty,
            ReturnCitations = service.SupportsCitations ? parms.ReturnCitations : null,
            SearchRecencyFilter = service.SupportsSearchRecency ? parms.SearchRecency : null,
            Search = parms.SearchEnabled ? true : null
        };

        var url = ApiClient.ChatUrl(service, customBaseUrl);
        var (response, _) = await ApiClient.Instance.RequestAsync<OpenAiRequest, OpenAiResponse>(url, request, service, apiKey);

        if (response.Error != null)
            throw new ApiError(ApiErrorKind.HttpError, response.Error.Message ?? "Unknown error");

        return response.Choices?.FirstOrDefault()?.Message?.Content
            ?? response.Choices?.FirstOrDefault()?.Message?.ReasoningContent ?? "";
    }

    private static async Task<string> SendChatMessageResponsesApi(
        AppService service, string apiKey, string model, List<ChatMessage> messages,
        ChatParameters parms, string? customBaseUrl)
    {
        var inputMessages = messages.Where(m => m.Role != "system")
            .Select(m => new OpenAiResponsesInputMessage { Role = m.Role, Content = m.Content }).ToList();

        OpenAiResponsesRequest request;
        if (inputMessages.Count == 1 && inputMessages[0].Role == "user")
        {
            request = OpenAiResponsesRequest.FromString(model, inputMessages[0].Content,
                string.IsNullOrEmpty(parms.SystemPrompt) ? null : parms.SystemPrompt);
        }
        else
        {
            request = OpenAiResponsesRequest.FromMessages(model, inputMessages,
                string.IsNullOrEmpty(parms.SystemPrompt) ? null : parms.SystemPrompt);
        }

        var baseUrl = customBaseUrl ?? service.BaseUrl;
        var normalizedBase = baseUrl.EndsWith("/") ? baseUrl : $"{baseUrl}/";
        var url = $"{normalizedBase}v1/responses";

        var (response, _) = await ApiClient.Instance.RequestAsync<OpenAiResponsesRequest, OpenAiResponsesApiResponse>(
            url, request, service, apiKey);

        if (response.Error != null)
            throw new ApiError(ApiErrorKind.HttpError, response.Error.Message ?? "Unknown error");

        return response.Output?
            .Select(msg => msg.Content?.FirstOrDefault(c => c.Type == "output_text")?.Text ?? msg.Content?.FirstOrDefault()?.Text)
            .FirstOrDefault(t => t != null) ?? "";
    }

    private static async Task<string> SendChatMessageClaude(
        AppService service, string apiKey, string model, List<ChatMessage> messages, ChatParameters parms)
    {
        var claudeMessages = messages.Where(m => m.Role != "system")
            .Select(m => new ClaudeMessage { Role = m.Role == "assistant" ? "assistant" : "user", Content = m.Content }).ToList();

        var request = new ClaudeRequest
        {
            Model = model,
            MaxTokens = parms.MaxTokens ?? 4096,
            Messages = claudeMessages,
            Temperature = parms.Temperature,
            TopP = parms.TopP,
            TopK = parms.TopK,
            System = string.IsNullOrEmpty(parms.SystemPrompt) ? null : parms.SystemPrompt,
            Search = parms.SearchEnabled ? true : null
        };

        var baseUrl = service.BaseUrl.EndsWith("/") ? service.BaseUrl : $"{service.BaseUrl}/";
        var url = $"{baseUrl}v1/messages";

        var (response, _) = await ApiClient.Instance.RequestAsync<ClaudeRequest, ClaudeResponse>(url, request, service, apiKey);

        if (response.Error != null)
            throw new ApiError(ApiErrorKind.HttpError, response.Error.Message ?? "Unknown error");

        return response.Content?.FirstOrDefault(c => c.Type == "text")?.Text ?? "";
    }

    private static async Task<string> SendChatMessageGemini(
        AppService service, string apiKey, string model, List<ChatMessage> messages, ChatParameters parms)
    {
        var geminiMessages = messages.Where(m => m.Role != "system")
            .Select(m => new GeminiContent
            {
                Parts = new() { new GeminiPart { Text = m.Content } },
                Role = m.Role == "assistant" ? "model" : "user"
            }).ToList();

        var config = new GeminiGenerationConfig
        {
            Temperature = parms.Temperature,
            TopP = parms.TopP,
            TopK = parms.TopK,
            MaxOutputTokens = parms.MaxTokens
        };

        var systemInstruction = string.IsNullOrEmpty(parms.SystemPrompt) ? null
            : new GeminiContent { Parts = new() { new GeminiPart { Text = parms.SystemPrompt } } };

        var request = new GeminiRequest
        {
            Contents = geminiMessages,
            GenerationConfig = config,
            SystemInstruction = systemInstruction
        };

        var url = ApiClient.GeminiGenerateUrl(service.BaseUrl, model, apiKey);
        var dummyService = new AppService { Id = "gemini_temp", ApiFormatStr = "GOOGLE" };

        var (response, _) = await ApiClient.Instance.RequestAsync<GeminiRequest, GeminiResponse>(url, request, dummyService, apiKey);

        if (response.Error != null)
            throw new ApiError(ApiErrorKind.HttpError, response.Error.Message ?? "Unknown error");

        return response.Candidates?.FirstOrDefault()?.Content?.Parts?.FirstOrDefault()?.Text ?? "";
    }
}
