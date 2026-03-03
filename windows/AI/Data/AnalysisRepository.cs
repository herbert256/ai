using System.Text.Json;

namespace AI.Data;

public class AnalysisRepository
{
    public static readonly AnalysisRepository Instance = new();

    private const int RetryDelayMs = 500;
    public const string TestPrompt = "Reply with exactly: OK";
    private const double XaiCostTicksDivisor = 10_000_000_000.0;

    // Cost extraction

    public static double? ExtractApiCost(OpenAiUsage? usage, AppService? provider = null)
    {
        if (usage == null) return null;

        if (provider?.ExtractApiCost == true && usage.Cost.HasValue)
            return usage.Cost.Value;

        if (provider?.CostTicksDivisor is { } divisor && usage.CostInUsdTicks.HasValue)
            return (double)usage.CostInUsdTicks.Value / divisor;

        if (provider?.CostTicksDivisor == null && usage.CostInUsdTicks.HasValue)
            return (double)usage.CostInUsdTicks.Value / XaiCostTicksDivisor;

        return null;
    }

    public static double? ExtractApiCost(ClaudeUsage? usage)
    {
        if (usage == null) return null;
        if (usage.Cost.HasValue) return usage.Cost.Value;
        if (usage.CostInUsdTicks.HasValue) return (double)usage.CostInUsdTicks.Value / 10_000_000_000.0;
        if (usage.CostUsd?.TotalCost.HasValue == true) return usage.CostUsd.TotalCost;
        return null;
    }

    public static double? ExtractApiCost(GeminiUsageMetadata? usage)
    {
        if (usage == null) return null;
        if (usage.Cost.HasValue) return usage.Cost.Value;
        if (usage.CostInUsdTicks.HasValue) return (double)usage.CostInUsdTicks.Value / 10_000_000_000.0;
        if (usage.CostUsd?.TotalCost.HasValue == true) return usage.CostUsd.TotalCost;
        return null;
    }

    // Prompt building

    public string FormatCurrentDate()
    {
        var today = DateTime.Now;
        var dayOfWeek = today.ToString("dddd");
        var month = today.ToString("MMMM");
        var day = today.Day;
        var suffix = (day % 100) switch
        {
            11 or 12 or 13 => "th",
            _ => (day % 10) switch
            {
                1 => "st",
                2 => "nd",
                3 => "rd",
                _ => "th"
            }
        };
        return $"{dayOfWeek}, {month} {day}{suffix}";
    }

    public string BuildPrompt(string template, string content, ViewModels.Agent? agent = null)
    {
        var result = template
            .Replace("@FEN@", content)
            .Replace("@DATE@", FormatCurrentDate());
        if (agent != null)
        {
            result = result
                .Replace("@MODEL@", agent.Model)
                .Replace("@PROVIDER@", agent.Provider?.DisplayName ?? "")
                .Replace("@AGENT@", agent.Name);
        }
        return result;
    }

    // Parameter merging

    public AgentParameters MergeParameters(AgentParameters agentParams, AgentParameters? overrideParams)
    {
        if (overrideParams == null) return agentParams;
        return new AgentParameters
        {
            Temperature = overrideParams.Temperature ?? agentParams.Temperature,
            MaxTokens = overrideParams.MaxTokens ?? agentParams.MaxTokens,
            TopP = overrideParams.TopP ?? agentParams.TopP,
            TopK = overrideParams.TopK ?? agentParams.TopK,
            FrequencyPenalty = overrideParams.FrequencyPenalty ?? agentParams.FrequencyPenalty,
            PresencePenalty = overrideParams.PresencePenalty ?? agentParams.PresencePenalty,
            SystemPrompt = !string.IsNullOrEmpty(overrideParams.SystemPrompt) ? overrideParams.SystemPrompt : agentParams.SystemPrompt,
            StopSequences = overrideParams.StopSequences?.Count > 0 ? overrideParams.StopSequences : agentParams.StopSequences,
            Seed = overrideParams.Seed ?? agentParams.Seed,
            ResponseFormatJson = overrideParams.ResponseFormatJson || agentParams.ResponseFormatJson,
            SearchEnabled = overrideParams.SearchEnabled || agentParams.SearchEnabled,
            ReturnCitations = overrideParams.ReturnCitations || agentParams.ReturnCitations,
            SearchRecency = overrideParams.SearchRecency ?? agentParams.SearchRecency
        };
    }

    public AgentParameters ValidateParams(AgentParameters p)
    {
        var result = new AgentParameters
        {
            Temperature = p.Temperature.HasValue ? Math.Clamp(p.Temperature.Value, 0, 2) : null,
            MaxTokens = p.MaxTokens.HasValue ? Math.Max(p.MaxTokens.Value, 1) : null,
            TopP = p.TopP.HasValue ? Math.Clamp(p.TopP.Value, 0, 1) : null,
            TopK = p.TopK.HasValue ? Math.Max(p.TopK.Value, 1) : null,
            FrequencyPenalty = p.FrequencyPenalty.HasValue ? Math.Clamp(p.FrequencyPenalty.Value, -2, 2) : null,
            PresencePenalty = p.PresencePenalty.HasValue ? Math.Clamp(p.PresencePenalty.Value, -2, 2) : null,
            SystemPrompt = p.SystemPrompt,
            StopSequences = p.StopSequences,
            Seed = p.Seed,
            ResponseFormatJson = p.ResponseFormatJson,
            SearchEnabled = p.SearchEnabled,
            ReturnCitations = p.ReturnCitations,
            SearchRecency = p.SearchRecency
        };
        return result;
    }

    public bool UsesResponsesApi(AppService service, string model)
    {
        var lowerModel = model.ToLowerInvariant();
        if (service.EndpointRules.Count == 0) return false;
        return service.EndpointRules.Any(r =>
            lowerModel.StartsWith(r.ModelPrefix.ToLowerInvariant()) && r.EndpointType == "responses");
    }

    // Retry logic

    public async Task<T> WithRetry<T>(string label, Func<Task<T>> makeCall, Func<T, bool> isSuccess, Func<Exception, T> errorResult)
    {
        try
        {
            var result = await makeCall();
            if (isSuccess(result)) return result;
            await Task.Delay(RetryDelayMs);
            try { return await makeCall(); }
            catch { return errorResult(new Exception("Retry failed")); }
        }
        catch (Exception ex)
        {
            try
            {
                await Task.Delay(RetryDelayMs);
                return await makeCall();
            }
            catch (Exception ex2)
            {
                return errorResult(ex2);
            }
        }
    }

    // Format helpers

    public string? FormatUsageJson(object? usage)
    {
        if (usage == null) return null;
        try
        {
            return JsonSerializer.Serialize(usage, new JsonSerializerOptions
            {
                WriteIndented = true,
                DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull
            });
        }
        catch { return null; }
    }

    public string FormatHeaders(Dictionary<string, string> headers) =>
        string.Join("\n", headers.Select(h => $"{h.Key}: {h.Value}"));

    // Message conversion

    public List<OpenAiMessage> ConvertToOpenAiMessages(List<ChatMessage> messages, string? systemPrompt = null)
    {
        var result = new List<OpenAiMessage>();
        if (!string.IsNullOrEmpty(systemPrompt))
            result.Add(new OpenAiMessage { Role = "system", Content = systemPrompt });
        result.AddRange(messages.Select(m => new OpenAiMessage { Role = m.Role, Content = m.Content }));
        return result;
    }
}
