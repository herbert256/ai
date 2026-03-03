using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace AI.Data;

public enum ApiFormat
{
    [JsonPropertyName("OPENAI_COMPATIBLE")]
    OpenAiCompatible,
    [JsonPropertyName("ANTHROPIC")]
    Anthropic,
    [JsonPropertyName("GOOGLE")]
    Google
}

public class EndpointRule
{
    [JsonPropertyName("modelPrefix")]
    public string ModelPrefix { get; set; } = "";

    [JsonPropertyName("endpointType")]
    public string EndpointType { get; set; } = "chat";
}

public class AppService
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("displayName")]
    public string DisplayName { get; set; } = "";

    [JsonPropertyName("baseUrl")]
    public string BaseUrl { get; set; } = "";

    [JsonPropertyName("adminUrl")]
    public string AdminUrl { get; set; } = "";

    [JsonPropertyName("defaultModel")]
    public string DefaultModel { get; set; } = "";

    [JsonPropertyName("openRouterName")]
    public string? OpenRouterName { get; set; }

    [JsonPropertyName("apiFormat")]
    public string ApiFormatStr { get; set; } = "OPENAI_COMPATIBLE";

    [JsonPropertyName("chatPath")]
    public string ChatPath { get; set; } = "v1/chat/completions";

    [JsonPropertyName("modelsPath")]
    public string? ModelsPath { get; set; } = "v1/models";

    [JsonPropertyName("prefsKey")]
    public string PrefsKey { get; set; } = "";

    [JsonPropertyName("seedFieldName")]
    public string SeedFieldName { get; set; } = "seed";

    [JsonPropertyName("supportsCitations")]
    public bool SupportsCitations { get; set; }

    [JsonPropertyName("supportsSearchRecency")]
    public bool SupportsSearchRecency { get; set; }

    [JsonPropertyName("extractApiCost")]
    public bool ExtractApiCost { get; set; }

    [JsonPropertyName("costTicksDivisor")]
    public double? CostTicksDivisor { get; set; }

    [JsonPropertyName("modelListFormat")]
    public string ModelListFormat { get; set; } = "object";

    [JsonPropertyName("modelFilter")]
    public string? ModelFilter { get; set; }

    [JsonPropertyName("litellmPrefix")]
    public string? LitellmPrefix { get; set; }

    [JsonPropertyName("hardcodedModels")]
    public List<string>? HardcodedModels { get; set; }

    [JsonPropertyName("defaultModelSource")]
    public string? DefaultModelSource { get; set; }

    [JsonPropertyName("endpointRules")]
    public List<EndpointRule> EndpointRules { get; set; } = new();

    [JsonIgnore]
    public ApiFormat ApiFormat => ApiFormatStr switch
    {
        "ANTHROPIC" => ApiFormat.Anthropic,
        "GOOGLE" => ApiFormat.Google,
        _ => ApiFormat.OpenAiCompatible
    };

    private Regex? _modelFilterRegex;
    [JsonIgnore]
    public Regex? ModelFilterRegex
    {
        get
        {
            if (_modelFilterRegex == null && ModelFilter != null)
            {
                try { _modelFilterRegex = new Regex(ModelFilter, RegexOptions.IgnoreCase); }
                catch { /* invalid pattern */ }
            }
            return _modelFilterRegex;
        }
    }

    // Static registry accessors
    public static List<AppService> Entries => ProviderRegistry.Instance.GetAll();
    public static AppService? FindById(string id) => ProviderRegistry.Instance.FindById(id);

    public override bool Equals(object? obj) => obj is AppService s && s.Id == Id;
    public override int GetHashCode() => Id.GetHashCode();
}

public class ProviderDefinition
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("displayName")]
    public string DisplayName { get; set; } = "";

    [JsonPropertyName("baseUrl")]
    public string BaseUrl { get; set; } = "";

    [JsonPropertyName("adminUrl")]
    public string? AdminUrl { get; set; }

    [JsonPropertyName("defaultModel")]
    public string DefaultModel { get; set; } = "";

    [JsonPropertyName("openRouterName")]
    public string? OpenRouterName { get; set; }

    [JsonPropertyName("apiFormat")]
    public string? ApiFormatStr { get; set; }

    [JsonPropertyName("chatPath")]
    public string? ChatPath { get; set; }

    [JsonPropertyName("modelsPath")]
    public string? ModelsPath { get; set; }

    [JsonPropertyName("prefsKey")]
    public string? PrefsKey { get; set; }

    [JsonPropertyName("seedFieldName")]
    public string? SeedFieldName { get; set; }

    [JsonPropertyName("supportsCitations")]
    public bool? SupportsCitations { get; set; }

    [JsonPropertyName("supportsSearchRecency")]
    public bool? SupportsSearchRecency { get; set; }

    [JsonPropertyName("extractApiCost")]
    public bool? ExtractApiCost { get; set; }

    [JsonPropertyName("costTicksDivisor")]
    public double? CostTicksDivisor { get; set; }

    [JsonPropertyName("modelListFormat")]
    public string? ModelListFormat { get; set; }

    [JsonPropertyName("modelFilter")]
    public string? ModelFilter { get; set; }

    [JsonPropertyName("litellmPrefix")]
    public string? LitellmPrefix { get; set; }

    [JsonPropertyName("hardcodedModels")]
    public List<string>? HardcodedModels { get; set; }

    [JsonPropertyName("defaultModelSource")]
    public string? DefaultModelSource { get; set; }

    [JsonPropertyName("endpointRules")]
    public List<EndpointRule>? EndpointRules { get; set; }

    public AppService ToAppService() => new()
    {
        Id = Id,
        DisplayName = DisplayName,
        BaseUrl = BaseUrl,
        AdminUrl = AdminUrl ?? "",
        DefaultModel = DefaultModel,
        OpenRouterName = OpenRouterName,
        ApiFormatStr = ApiFormatStr ?? "OPENAI_COMPATIBLE",
        ChatPath = ChatPath ?? "v1/chat/completions",
        ModelsPath = ModelsPath,
        PrefsKey = string.IsNullOrEmpty(PrefsKey) ? Id.ToLowerInvariant() : PrefsKey,
        SeedFieldName = SeedFieldName ?? "seed",
        SupportsCitations = SupportsCitations ?? false,
        SupportsSearchRecency = SupportsSearchRecency ?? false,
        ExtractApiCost = ExtractApiCost ?? false,
        CostTicksDivisor = CostTicksDivisor,
        ModelListFormat = ModelListFormat ?? "object",
        ModelFilter = ModelFilter,
        LitellmPrefix = LitellmPrefix,
        HardcodedModels = HardcodedModels,
        DefaultModelSource = DefaultModelSource,
        EndpointRules = EndpointRules ?? new()
    };

    public static ProviderDefinition FromAppService(AppService s) => new()
    {
        Id = s.Id,
        DisplayName = s.DisplayName,
        BaseUrl = s.BaseUrl,
        AdminUrl = s.AdminUrl,
        DefaultModel = s.DefaultModel,
        OpenRouterName = s.OpenRouterName,
        ApiFormatStr = s.ApiFormatStr,
        ChatPath = s.ChatPath,
        ModelsPath = s.ModelsPath,
        PrefsKey = s.PrefsKey,
        SeedFieldName = s.SeedFieldName,
        SupportsCitations = s.SupportsCitations,
        SupportsSearchRecency = s.SupportsSearchRecency,
        ExtractApiCost = s.ExtractApiCost,
        CostTicksDivisor = s.CostTicksDivisor,
        ModelListFormat = s.ModelListFormat,
        ModelFilter = s.ModelFilter,
        LitellmPrefix = s.LitellmPrefix,
        HardcodedModels = s.HardcodedModels,
        DefaultModelSource = s.DefaultModelSource,
        EndpointRules = s.EndpointRules.Count == 0 ? null : s.EndpointRules
    };
}
