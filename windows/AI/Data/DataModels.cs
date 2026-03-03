using System.Text.Json.Serialization;

namespace AI.Data;

public class AgentParameters
{
    [JsonPropertyName("temperature")]
    public float? Temperature { get; set; }

    [JsonPropertyName("maxTokens")]
    public int? MaxTokens { get; set; }

    [JsonPropertyName("topP")]
    public float? TopP { get; set; }

    [JsonPropertyName("topK")]
    public int? TopK { get; set; }

    [JsonPropertyName("frequencyPenalty")]
    public float? FrequencyPenalty { get; set; }

    [JsonPropertyName("presencePenalty")]
    public float? PresencePenalty { get; set; }

    [JsonPropertyName("systemPrompt")]
    public string? SystemPrompt { get; set; }

    [JsonPropertyName("stopSequences")]
    public List<string>? StopSequences { get; set; }

    [JsonPropertyName("seed")]
    public int? Seed { get; set; }

    [JsonPropertyName("responseFormatJson")]
    public bool ResponseFormatJson { get; set; }

    [JsonPropertyName("searchEnabled")]
    public bool SearchEnabled { get; set; }

    [JsonPropertyName("returnCitations")]
    public bool ReturnCitations { get; set; } = true;

    [JsonPropertyName("searchRecency")]
    public string? SearchRecency { get; set; }
}

public class ChatMessage
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = Guid.NewGuid().ToString();

    [JsonPropertyName("role")]
    public string Role { get; set; } = "";

    [JsonPropertyName("content")]
    public string Content { get; set; } = "";

    [JsonPropertyName("timestamp")]
    public DateTimeOffset Timestamp { get; set; } = DateTimeOffset.UtcNow;

    public ChatMessage() { }

    public ChatMessage(string role, string content)
    {
        Role = role;
        Content = content;
        Timestamp = DateTimeOffset.UtcNow;
    }
}

public class ChatParameters
{
    [JsonPropertyName("systemPrompt")]
    public string SystemPrompt { get; set; } = "";

    [JsonPropertyName("temperature")]
    public float? Temperature { get; set; }

    [JsonPropertyName("maxTokens")]
    public int? MaxTokens { get; set; }

    [JsonPropertyName("topP")]
    public float? TopP { get; set; }

    [JsonPropertyName("topK")]
    public int? TopK { get; set; }

    [JsonPropertyName("frequencyPenalty")]
    public float? FrequencyPenalty { get; set; }

    [JsonPropertyName("presencePenalty")]
    public float? PresencePenalty { get; set; }

    [JsonPropertyName("searchEnabled")]
    public bool SearchEnabled { get; set; }

    [JsonPropertyName("returnCitations")]
    public bool ReturnCitations { get; set; } = true;

    [JsonPropertyName("searchRecency")]
    public string? SearchRecency { get; set; }
}

public class ChatSession
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = Guid.NewGuid().ToString();

    [JsonPropertyName("providerId")]
    public string ProviderId { get; set; } = "";

    [JsonPropertyName("model")]
    public string Model { get; set; } = "";

    [JsonPropertyName("messages")]
    public List<ChatMessage> Messages { get; set; } = new();

    [JsonPropertyName("parameters")]
    public ChatParameters Parameters { get; set; } = new();

    [JsonPropertyName("createdAt")]
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;

    [JsonPropertyName("updatedAt")]
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;

    [JsonIgnore]
    public string Preview =>
        Messages.FirstOrDefault(m => m.Role == "user")?.Content is { } c && c.Length > 50
            ? c[..50] : Messages.FirstOrDefault(m => m.Role == "user")?.Content ?? "Empty chat";

    [JsonIgnore]
    public AppService? Provider => AppService.FindById(ProviderId);
}

public class DualChatConfig
{
    [JsonPropertyName("model1ProviderId")]
    public string Model1ProviderId { get; set; } = "";

    [JsonPropertyName("model1Name")]
    public string Model1Name { get; set; } = "";

    [JsonPropertyName("model1SystemPrompt")]
    public string Model1SystemPrompt { get; set; } = "";

    [JsonPropertyName("model1Params")]
    public ChatParameters Model1Params { get; set; } = new();

    [JsonPropertyName("model2ProviderId")]
    public string Model2ProviderId { get; set; } = "";

    [JsonPropertyName("model2Name")]
    public string Model2Name { get; set; } = "";

    [JsonPropertyName("model2SystemPrompt")]
    public string Model2SystemPrompt { get; set; } = "";

    [JsonPropertyName("model2Params")]
    public ChatParameters Model2Params { get; set; } = new();

    [JsonPropertyName("subject")]
    public string Subject { get; set; } = "";

    [JsonPropertyName("interactionCount")]
    public int InteractionCount { get; set; } = 10;

    [JsonPropertyName("firstPrompt")]
    public string FirstPrompt { get; set; } = "Let's talk about %subject%";

    [JsonPropertyName("secondPrompt")]
    public string SecondPrompt { get; set; } = "What do you think about: %answer%";
}

public class TokenUsage
{
    [JsonPropertyName("inputTokens")]
    public int InputTokens { get; set; }

    [JsonPropertyName("outputTokens")]
    public int OutputTokens { get; set; }

    [JsonPropertyName("apiCost")]
    public double? ApiCost { get; set; }

    [JsonIgnore]
    public int TotalTokens => InputTokens + OutputTokens;

    public TokenUsage() { }

    public TokenUsage(int inputTokens, int outputTokens, double? apiCost = null)
    {
        InputTokens = inputTokens;
        OutputTokens = outputTokens;
        ApiCost = apiCost;
    }
}

public enum ReportStatus
{
    Pending,
    Running,
    Success,
    Error,
    Stopped
}

public class ReportAgent
{
    public string Id { get; set; }
    public string AgentId { get; set; }
    public string AgentName { get; set; }
    public string ProviderId { get; set; }
    public string Model { get; set; }
    public ReportStatus Status { get; set; }

    public ReportAgent(string agentId, string agentName, string providerId, string model, ReportStatus status = ReportStatus.Pending)
    {
        Id = agentId;
        AgentId = agentId;
        AgentName = agentName;
        ProviderId = providerId;
        Model = model;
        Status = status;
    }
}

public class AnalysisResponse
{
    public string Id { get; } = Guid.NewGuid().ToString();
    public AppService Service { get; set; }
    public string? Analysis { get; set; }
    public string? Error { get; set; }
    public TokenUsage? TokenUsage { get; set; }
    public string? AgentName { get; set; }
    public string? PromptUsed { get; set; }
    public List<string>? Citations { get; set; }
    public List<SearchResult>? SearchResults { get; set; }
    public List<string>? RelatedQuestions { get; set; }
    public string? RawUsageJson { get; set; }
    public string? HttpHeaders { get; set; }
    public int? HttpStatusCode { get; set; }

    public bool IsSuccess => Analysis != null && Error == null;

    public string DisplayName =>
        !string.IsNullOrEmpty(AgentName) ? $"{AgentName} ({Service.DisplayName})" : Service.DisplayName;

    public AnalysisResponse(AppService service, string? analysis = null, string? error = null,
        TokenUsage? tokenUsage = null, string? agentName = null, int? httpStatusCode = null)
    {
        Service = service;
        Analysis = analysis;
        Error = error;
        TokenUsage = tokenUsage;
        AgentName = agentName;
        HttpStatusCode = httpStatusCode;
    }
}

public class SearchResult
{
    [JsonPropertyName("name")]
    public string? Name { get; set; }

    [JsonPropertyName("url")]
    public string? Url { get; set; }

    [JsonPropertyName("snippet")]
    public string? Snippet { get; set; }
}

public class StoredReport
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("title")]
    public string Title { get; set; } = "";

    [JsonPropertyName("prompt")]
    public string Prompt { get; set; } = "";

    [JsonPropertyName("timestamp")]
    public long Timestamp { get; set; }

    [JsonPropertyName("results")]
    public List<StoredAnalysisResult> Results { get; set; } = new();

    [JsonIgnore]
    public DateTimeOffset TimestampDate => DateTimeOffset.FromUnixTimeMilliseconds(Timestamp);

    public StoredReport() { }

    public StoredReport(string id, string title, string prompt, DateTimeOffset timestamp)
    {
        Id = id;
        Title = title;
        Prompt = prompt;
        Timestamp = timestamp.ToUnixTimeMilliseconds();
    }
}

public class StoredAnalysisResult
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = Guid.NewGuid().ToString();

    [JsonPropertyName("providerId")]
    public string ProviderId { get; set; } = "";

    [JsonPropertyName("model")]
    public string Model { get; set; } = "";

    [JsonPropertyName("agentName")]
    public string? AgentName { get; set; }

    [JsonPropertyName("analysis")]
    public string? Analysis { get; set; }

    [JsonPropertyName("error")]
    public string? Error { get; set; }

    [JsonPropertyName("inputTokens")]
    public int InputTokens { get; set; }

    [JsonPropertyName("outputTokens")]
    public int OutputTokens { get; set; }

    [JsonPropertyName("apiCost")]
    public double? ApiCost { get; set; }

    [JsonPropertyName("citations")]
    public List<string>? Citations { get; set; }

    [JsonPropertyName("rawUsageJson")]
    public string? RawUsageJson { get; set; }

    public StoredAnalysisResult() { }

    public static StoredAnalysisResult FromResponse(AnalysisResponse response) => new()
    {
        ProviderId = response.Service.Id,
        AgentName = response.AgentName,
        Analysis = response.Analysis,
        Error = response.Error,
        InputTokens = response.TokenUsage?.InputTokens ?? 0,
        OutputTokens = response.TokenUsage?.OutputTokens ?? 0,
        ApiCost = response.TokenUsage?.ApiCost,
        Citations = response.Citations,
        RawUsageJson = response.RawUsageJson
    };
}

public class UsageStats
{
    [JsonPropertyName("providerId")]
    public string ProviderId { get; set; } = "";

    [JsonPropertyName("model")]
    public string Model { get; set; } = "";

    [JsonPropertyName("callCount")]
    public int CallCount { get; set; }

    [JsonPropertyName("inputTokens")]
    public long InputTokens { get; set; }

    [JsonPropertyName("outputTokens")]
    public long OutputTokens { get; set; }

    [JsonIgnore]
    public long TotalTokens => InputTokens + OutputTokens;

    [JsonIgnore]
    public string Key => $"{ProviderId}::{Model}";

    public UsageStats() { }

    public UsageStats(string providerId, string model)
    {
        ProviderId = providerId;
        Model = model;
    }
}

public class PromptHistoryEntry
{
    [JsonPropertyName("timestamp")]
    public long Timestamp { get; set; }

    [JsonPropertyName("title")]
    public string Title { get; set; } = "";

    [JsonPropertyName("prompt")]
    public string Prompt { get; set; } = "";

    [JsonIgnore]
    public DateTimeOffset TimestampDate => DateTimeOffset.FromUnixTimeMilliseconds(Timestamp);

    public PromptHistoryEntry() { }

    public PromptHistoryEntry(string title, string prompt)
    {
        Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        Title = title;
        Prompt = prompt;
    }
}
