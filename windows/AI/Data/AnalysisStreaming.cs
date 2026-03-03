using System.Runtime.CompilerServices;
using System.Text.Json;

namespace AI.Data;

public static class AnalysisStreaming
{
    private static readonly AnalysisRepository Repo = AnalysisRepository.Instance;

    public static IAsyncEnumerable<string> SendChatMessageStream(
        AppService service, string apiKey, string model, List<ChatMessage> messages,
        ChatParameters parms, string? customBaseUrl = null, CancellationToken ct = default)
    {
        return service.ApiFormat switch
        {
            ApiFormat.Anthropic => StreamChatClaude(service, apiKey, model, messages, parms, ct),
            ApiFormat.Google => StreamChatGemini(service, apiKey, model, messages, parms, ct),
            _ => StreamChatOpenAiCompatible(service, apiKey, model, messages, parms, customBaseUrl, ct)
        };
    }

    private static async IAsyncEnumerable<string> StreamChatOpenAiCompatible(
        AppService service, string apiKey, string model, List<ChatMessage> messages,
        ChatParameters parms, string? customBaseUrl, [EnumeratorCancellation] CancellationToken ct)
    {
        if (Repo.UsesResponsesApi(service, model))
        {
            await foreach (var chunk in StreamResponsesApi(service, apiKey, model, messages, parms, customBaseUrl, ct))
                yield return chunk;
            yield break;
        }

        var openAiMessages = Repo.ConvertToOpenAiMessages(messages, parms.SystemPrompt);
        var request = new OpenAiStreamRequest
        {
            Model = model,
            Messages = openAiMessages,
            Stream = true,
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
        using var response = await ApiClient.Instance.StreamRequestAsync(url, request, service, apiKey);
        using var stream = await response.Content.ReadAsStreamAsync(ct);
        using var reader = new StreamReader(stream);

        while (!reader.EndOfStream && !ct.IsCancellationRequested)
        {
            var line = await reader.ReadLineAsync(ct);
            if (line == null) break;
            if (!line.StartsWith("data: ")) continue;

            var data = line[6..];
            if (data == "[DONE]") break;

            OpenAiStreamChunk? chunk;
            try { chunk = JsonSerializer.Deserialize<OpenAiStreamChunk>(data); }
            catch { continue; }

            if (chunk?.Choices?.FirstOrDefault()?.Delta?.Content is { } content)
                yield return content;
            if (chunk?.Choices?.FirstOrDefault()?.Delta?.ReasoningContent is { } reasoning)
                yield return reasoning;
        }
    }

    private static async IAsyncEnumerable<string> StreamResponsesApi(
        AppService service, string apiKey, string model, List<ChatMessage> messages,
        ChatParameters parms, string? customBaseUrl, [EnumeratorCancellation] CancellationToken ct)
    {
        var inputMessages = messages.Where(m => m.Role != "system")
            .Select(m => new OpenAiResponsesInputMessage { Role = m.Role, Content = m.Content }).ToList();

        var request = new OpenAiResponsesStreamRequest
        {
            Model = model,
            Input = inputMessages,
            Instructions = string.IsNullOrEmpty(parms.SystemPrompt) ? null : parms.SystemPrompt,
            Stream = true
        };

        var baseUrl = customBaseUrl ?? service.BaseUrl;
        var normalizedBase = baseUrl.EndsWith("/") ? baseUrl : $"{baseUrl}/";
        var url = $"{normalizedBase}v1/responses";

        using var response = await ApiClient.Instance.StreamRequestAsync(url, request, service, apiKey);
        using var stream = await response.Content.ReadAsStreamAsync(ct);
        using var reader = new StreamReader(stream);

        while (!reader.EndOfStream && !ct.IsCancellationRequested)
        {
            var line = await reader.ReadLineAsync(ct);
            if (line == null) break;
            if (!line.StartsWith("data: ")) continue;

            var data = line[6..];
            if (data == "[DONE]") break;

            try
            {
                using var doc = JsonDocument.Parse(data);
                if (doc.RootElement.TryGetProperty("delta", out var delta) && delta.ValueKind == JsonValueKind.String)
                    yield return delta.GetString()!;
            }
            catch { continue; }
        }
    }

    private static async IAsyncEnumerable<string> StreamChatClaude(
        AppService service, string apiKey, string model, List<ChatMessage> messages,
        ChatParameters parms, [EnumeratorCancellation] CancellationToken ct)
    {
        var claudeMessages = messages.Where(m => m.Role != "system")
            .Select(m => new ClaudeMessage { Role = m.Role == "assistant" ? "assistant" : "user", Content = m.Content }).ToList();

        var request = new ClaudeStreamRequest
        {
            Model = model,
            Messages = claudeMessages,
            Stream = true,
            MaxTokens = parms.MaxTokens ?? 4096,
            Temperature = parms.Temperature,
            TopP = parms.TopP,
            TopK = parms.TopK,
            System = string.IsNullOrEmpty(parms.SystemPrompt) ? null : parms.SystemPrompt,
            Search = parms.SearchEnabled ? true : null
        };

        var baseUrl = service.BaseUrl.EndsWith("/") ? service.BaseUrl : $"{service.BaseUrl}/";
        var url = $"{baseUrl}v1/messages";

        using var response = await ApiClient.Instance.StreamRequestAsync(url, request, service, apiKey);
        using var stream = await response.Content.ReadAsStreamAsync(ct);
        using var reader = new StreamReader(stream);

        var currentEvent = "";
        while (!reader.EndOfStream && !ct.IsCancellationRequested)
        {
            var line = await reader.ReadLineAsync(ct);
            if (line == null) break;

            if (line.StartsWith("event: "))
            {
                currentEvent = line[7..];
                if (currentEvent == "message_stop") break;
            }
            else if (line.StartsWith("data: ") && currentEvent == "content_block_delta")
            {
                var data = line[6..];
                try
                {
                    var evt = JsonSerializer.Deserialize<ClaudeStreamEvent>(data);
                    if (evt?.Delta?.Text is { } text)
                        yield return text;
                }
                catch { continue; }
            }
        }
    }

    private static async IAsyncEnumerable<string> StreamChatGemini(
        AppService service, string apiKey, string model, List<ChatMessage> messages,
        ChatParameters parms, [EnumeratorCancellation] CancellationToken ct)
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

        var url = ApiClient.GeminiGenerateUrl(service.BaseUrl, model, apiKey, stream: true);
        var dummyService = new AppService { Id = "gemini_temp", ApiFormatStr = "GOOGLE" };

        using var response = await ApiClient.Instance.StreamRequestAsync(url, request, dummyService, apiKey);
        using var stream = await response.Content.ReadAsStreamAsync(ct);
        using var reader = new StreamReader(stream);

        while (!reader.EndOfStream && !ct.IsCancellationRequested)
        {
            var line = await reader.ReadLineAsync(ct);
            if (line == null) break;
            if (!line.StartsWith("data: ")) continue;

            var data = line[6..];
            try
            {
                var chunk = JsonSerializer.Deserialize<GeminiStreamChunk>(data);
                if (chunk?.Candidates?.FirstOrDefault()?.Content?.Parts?.FirstOrDefault()?.Text is { } text)
                    yield return text;
            }
            catch { continue; }
        }
    }
}
