using System.Text.Json.Serialization;
using AI.Data;

namespace AI.ViewModels;

public enum ModelSource
{
    [JsonPropertyName("API")]
    Api,
    [JsonPropertyName("MANUAL")]
    Manual
}

public class ProviderConfig
{
    [JsonPropertyName("apiKey")]
    public string ApiKey { get; set; } = "";

    [JsonPropertyName("model")]
    public string Model { get; set; } = "";

    [JsonPropertyName("modelSource")]
    public string ModelSourceStr { get; set; } = "API";

    [JsonPropertyName("models")]
    public List<string> Models { get; set; } = new();

    [JsonPropertyName("adminUrl")]
    public string AdminUrl { get; set; } = "";

    [JsonPropertyName("modelListUrl")]
    public string ModelListUrl { get; set; } = "";

    [JsonPropertyName("parametersIds")]
    public List<string> ParametersIds { get; set; } = new();

    [JsonIgnore]
    public ModelSource ModelSource
    {
        get => ModelSourceStr == "MANUAL" ? ModelSource.Manual : ModelSource.Api;
        set => ModelSourceStr = value == ModelSource.Manual ? "MANUAL" : "API";
    }
}

public static class ProviderConfigDefaults
{
    public static ProviderConfig DefaultProviderConfig(AppService service)
    {
        var defaultModels = service.HardcodedModels ?? new();
        var defaultSource = service.DefaultModelSource != null
            ? (service.DefaultModelSource == "MANUAL" ? ModelSource.Manual : ModelSource.Api)
            : (defaultModels.Count == 0 ? ModelSource.Api : ModelSource.Manual);
        return new ProviderConfig
        {
            Model = service.DefaultModel,
            ModelSource = defaultSource,
            Models = defaultModels,
            AdminUrl = service.AdminUrl
        };
    }

    public static Dictionary<string, ProviderConfig> DefaultProvidersMap()
    {
        var map = new Dictionary<string, ProviderConfig>();
        foreach (var service in AppService.Entries)
            map[service.Id] = DefaultProviderConfig(service);
        return map;
    }
}

public enum Parameter
{
    Temperature,
    MaxTokens,
    TopP,
    TopK,
    FrequencyPenalty,
    PresencePenalty,
    SystemPrompt,
    StopSequences,
    Seed,
    ResponseFormat,
    SearchEnabled,
    ReturnCitations,
    SearchRecency
}

public class Endpoint
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = Guid.NewGuid().ToString();

    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("url")]
    public string Url { get; set; } = "";

    [JsonPropertyName("isDefault")]
    public bool IsDefault { get; set; }
}

public class Agent
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = Guid.NewGuid().ToString();

    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("providerId")]
    public string ProviderId { get; set; } = "";

    [JsonPropertyName("model")]
    public string Model { get; set; } = "";

    [JsonPropertyName("apiKey")]
    public string ApiKey { get; set; } = "";

    [JsonPropertyName("endpointId")]
    public string? EndpointId { get; set; }

    [JsonPropertyName("paramsIds")]
    public List<string> ParamsIds { get; set; } = new();

    [JsonPropertyName("systemPromptId")]
    public string? SystemPromptId { get; set; }

    [JsonIgnore]
    public AppService? Provider => AppService.FindById(ProviderId);
}

public class Flock
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = Guid.NewGuid().ToString();

    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("agentIds")]
    public List<string> AgentIds { get; set; } = new();

    [JsonPropertyName("paramsIds")]
    public List<string> ParamsIds { get; set; } = new();

    [JsonPropertyName("systemPromptId")]
    public string? SystemPromptId { get; set; }
}

public class SwarmMember
{
    [JsonPropertyName("providerId")]
    public string ProviderId { get; set; } = "";

    [JsonPropertyName("model")]
    public string Model { get; set; } = "";

    [JsonIgnore]
    public AppService? Provider => AppService.FindById(ProviderId);
}

public class Swarm
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = Guid.NewGuid().ToString();

    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("members")]
    public List<SwarmMember> Members { get; set; } = new();

    [JsonPropertyName("paramsIds")]
    public List<string> ParamsIds { get; set; } = new();

    [JsonPropertyName("systemPromptId")]
    public string? SystemPromptId { get; set; }
}

public class Parameters
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = Guid.NewGuid().ToString();

    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

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

    public AgentParameters ToAgentParameters() => new()
    {
        Temperature = Temperature,
        MaxTokens = MaxTokens,
        TopP = TopP,
        TopK = TopK,
        FrequencyPenalty = FrequencyPenalty,
        PresencePenalty = PresencePenalty,
        SystemPrompt = SystemPrompt,
        StopSequences = StopSequences,
        Seed = Seed,
        ResponseFormatJson = ResponseFormatJson,
        SearchEnabled = SearchEnabled,
        ReturnCitations = ReturnCitations,
        SearchRecency = SearchRecency
    };
}

public class SystemPrompt
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = Guid.NewGuid().ToString();

    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("prompt")]
    public string Prompt { get; set; } = "";
}

public class Prompt
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = Guid.NewGuid().ToString();

    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("agentId")]
    public string AgentId { get; set; } = "";

    [JsonPropertyName("promptText")]
    public string PromptText { get; set; } = "";

    public string ResolvePrompt(string? model = null, string? provider = null, string? agent = null, string? swarm = null)
    {
        var result = PromptText;
        if (model != null) result = result.Replace("@MODEL@", model);
        if (provider != null) result = result.Replace("@PROVIDER@", provider);
        if (agent != null) result = result.Replace("@AGENT@", agent);
        if (swarm != null) result = result.Replace("@SWARM@", swarm);
        result = result.Replace("@NOW@", DateTime.Now.ToString("yyyy-MM-dd HH:mm"));
        return result;
    }
}

public class Settings
{
    [JsonPropertyName("providers")]
    public Dictionary<string, ProviderConfig> Providers { get; set; } = new();

    [JsonPropertyName("agents")]
    public List<Agent> Agents { get; set; } = new();

    [JsonPropertyName("flocks")]
    public List<Flock> Flocks { get; set; } = new();

    [JsonPropertyName("swarms")]
    public List<Swarm> Swarms { get; set; } = new();

    [JsonPropertyName("parameters")]
    public List<Parameters> ParametersList { get; set; } = new();

    [JsonPropertyName("systemPrompts")]
    public List<SystemPrompt> SystemPrompts { get; set; } = new();

    [JsonPropertyName("prompts")]
    public List<Prompt> Prompts { get; set; } = new();

    [JsonPropertyName("endpoints")]
    public Dictionary<string, List<Endpoint>> Endpoints { get; set; } = new();

    [JsonPropertyName("providerStates")]
    public Dictionary<string, string> ProviderStates { get; set; } = new();

    private static readonly Dictionary<string, List<Endpoint>> BuiltInEndpoints = new()
    {
        ["OPENAI"] = new()
        {
            new() { Id = "openai-chat", Name = "Chat Completions", Url = "https://api.openai.com/v1/chat/completions", IsDefault = true },
            new() { Id = "openai-responses", Name = "Responses API", Url = "https://api.openai.com/v1/responses" }
        },
        ["MISTRAL"] = new()
        {
            new() { Id = "mistral-chat", Name = "Chat Completions", Url = "https://api.mistral.ai/v1/chat/completions", IsDefault = true },
            new() { Id = "mistral-codestral", Name = "Codestral", Url = "https://codestral.mistral.ai/v1/chat/completions" }
        },
        ["DEEPSEEK"] = new()
        {
            new() { Id = "deepseek-chat", Name = "Chat Completions", Url = "https://api.deepseek.com/chat/completions", IsDefault = true },
            new() { Id = "deepseek-beta", Name = "Beta (FIM)", Url = "https://api.deepseek.com/beta/completions" }
        },
        ["ZAI"] = new()
        {
            new() { Id = "zai-chat", Name = "Chat Completions", Url = "https://api.z.ai/api/paas/v4/chat/completions", IsDefault = true },
            new() { Id = "zai-coding", Name = "Coding", Url = "https://api.z.ai/api/coding/paas/v4/chat/completions" }
        }
    };

    // Provider State
    public string GetProviderState(AppService service)
    {
        ProviderStates.TryGetValue(service.Id, out var stored);
        if (stored == "inactive") return "inactive";
        if (string.IsNullOrEmpty(GetApiKey(service))) return "not-used";
        return stored ?? "ok";
    }

    public bool IsProviderActive(AppService service) => GetProviderState(service) == "ok";

    public List<AppService> GetActiveServices() =>
        AppService.Entries.Where(IsProviderActive).ToList();

    public void SetProviderState(AppService service, string state) =>
        ProviderStates[service.Id] = state;

    // Provider Config
    public ProviderConfig GetProvider(AppService service) =>
        Providers.TryGetValue(service.Id, out var c) ? c : ProviderConfigDefaults.DefaultProviderConfig(service);

    public void SetProvider(AppService service, ProviderConfig config) =>
        Providers[service.Id] = config;

    public string GetApiKey(AppService service) => GetProvider(service).ApiKey;

    public void SetApiKey(AppService service, string apiKey)
    {
        var config = GetProvider(service);
        config.ApiKey = apiKey;
        SetProvider(service, config);
    }

    public string GetModel(AppService service) => GetProvider(service).Model;

    public void SetModel(AppService service, string model)
    {
        var config = GetProvider(service);
        config.Model = model;
        SetProvider(service, config);
    }

    public List<string> GetModels(AppService service) => GetProvider(service).Models;

    public void SetModels(AppService service, List<string> models)
    {
        var config = GetProvider(service);
        config.Models = models;
        SetProvider(service, config);
    }

    public bool HasAnyApiKey() => Providers.Values.Any(c => !string.IsNullOrEmpty(c.ApiKey));

    // Agent helpers
    public Agent? GetAgentById(string id) => Agents.FirstOrDefault(a => a.Id == id);

    public AgentParameters ResolveAgentParameters(Agent agent) =>
        MergeParameters(agent.ParamsIds) ?? new AgentParameters();

    public string GetEffectiveApiKeyForAgent(Agent agent)
    {
        if (!string.IsNullOrEmpty(agent.ApiKey)) return agent.ApiKey;
        return agent.Provider != null ? GetApiKey(agent.Provider) : "";
    }

    public string GetEffectiveModelForAgent(Agent agent)
    {
        if (!string.IsNullOrEmpty(agent.Model)) return agent.Model;
        return agent.Provider != null ? GetModel(agent.Provider) : "";
    }

    public List<Agent> GetConfiguredAgents() => Agents.Where(a =>
        !string.IsNullOrEmpty(a.ApiKey) ||
        (a.Provider != null && !string.IsNullOrEmpty(GetApiKey(a.Provider)))
    ).ToList();

    // Flock helpers
    public Flock? GetFlockById(string id) => Flocks.FirstOrDefault(f => f.Id == id);
    public List<Agent> GetAgentsForFlock(Flock flock) => flock.AgentIds.Select(id => GetAgentById(id)).Where(a => a != null).Cast<Agent>().ToList();

    // Swarm helpers
    public Swarm? GetSwarmById(string id) => Swarms.FirstOrDefault(s => s.Id == id);

    // Prompt helpers
    public Prompt? GetPromptByName(string name) => Prompts.FirstOrDefault(p => p.Name.Equals(name, StringComparison.OrdinalIgnoreCase));
    public SystemPrompt? GetSystemPromptById(string id) => SystemPrompts.FirstOrDefault(sp => sp.Id == id);
    public Parameters? GetParametersById(string id) => ParametersList.FirstOrDefault(p => p.Id == id);
    public Prompt? GetPromptById(string id) => Prompts.FirstOrDefault(p => p.Id == id);

    // Endpoint helpers
    public List<Endpoint> GetEndpointsForProvider(AppService provider)
    {
        if (Endpoints.TryGetValue(provider.Id, out var custom) && custom.Count > 0) return custom;
        if (BuiltInEndpoints.TryGetValue(provider.Id, out var builtIn)) return builtIn;
        return DefaultEndpointForProvider(provider);
    }

    private List<Endpoint> DefaultEndpointForProvider(AppService provider)
    {
        var baseUrl = provider.BaseUrl.EndsWith("/") ? provider.BaseUrl : $"{provider.BaseUrl}/";
        var url = baseUrl + provider.ChatPath;
        var idPrefix = provider.Id.ToLowerInvariant();
        return new() { new Endpoint { Id = $"{idPrefix}-chat", Name = "Chat Completions", Url = url, IsDefault = true } };
    }

    public Endpoint? GetEndpointById(AppService provider, string endpointId) =>
        GetEndpointsForProvider(provider).FirstOrDefault(e => e.Id == endpointId);

    public Endpoint? GetDefaultEndpoint(AppService provider)
    {
        var eps = GetEndpointsForProvider(provider);
        return eps.FirstOrDefault(e => e.IsDefault) ?? eps.FirstOrDefault();
    }

    public string GetEffectiveEndpointUrl(AppService provider) =>
        GetDefaultEndpoint(provider)?.Url ?? provider.BaseUrl;

    public string GetEffectiveEndpointUrlForAgent(Agent agent)
    {
        if (agent.Provider == null) return "";
        if (agent.EndpointId != null)
        {
            var ep = GetEndpointById(agent.Provider, agent.EndpointId);
            if (ep != null) return ep.Url;
        }
        return GetEffectiveEndpointUrl(agent.Provider);
    }

    public void SetEndpoints(AppService provider, List<Endpoint> endpoints) =>
        Endpoints[provider.Id] = endpoints;

    // Model list URL
    public string GetModelListUrl(AppService service) => GetProvider(service).ModelListUrl;

    public string GetDefaultModelListUrl(AppService service)
    {
        if (service.ModelsPath == null) return "";
        var baseUrl = service.BaseUrl.EndsWith("/") ? service.BaseUrl : $"{service.BaseUrl}/";
        return baseUrl + service.ModelsPath;
    }

    public string GetEffectiveModelListUrl(AppService service)
    {
        var custom = GetModelListUrl(service);
        return string.IsNullOrEmpty(custom) ? GetDefaultModelListUrl(service) : custom;
    }

    // Parameters
    public List<string> GetParametersIds(AppService service) => GetProvider(service).ParametersIds;

    public void SetParametersIds(AppService service, List<string> paramsIds)
    {
        var config = GetProvider(service);
        config.ParametersIds = paramsIds;
        SetProvider(service, config);
    }

    public AgentParameters? MergeParameters(List<string> ids)
    {
        if (ids.Count == 0) return null;
        var presets = ids.Select(id => GetParametersById(id)).Where(p => p != null).Cast<Parameters>().ToList();
        if (presets.Count == 0) return null;

        var acc = new AgentParameters();
        foreach (var p in presets.Select(p => p.ToAgentParameters()))
        {
            if (p.Temperature.HasValue) acc.Temperature = p.Temperature;
            if (p.MaxTokens.HasValue) acc.MaxTokens = p.MaxTokens;
            if (p.TopP.HasValue) acc.TopP = p.TopP;
            if (p.TopK.HasValue) acc.TopK = p.TopK;
            if (p.FrequencyPenalty.HasValue) acc.FrequencyPenalty = p.FrequencyPenalty;
            if (p.PresencePenalty.HasValue) acc.PresencePenalty = p.PresencePenalty;
            if (p.SystemPrompt != null) acc.SystemPrompt = p.SystemPrompt;
            if (p.StopSequences != null) acc.StopSequences = p.StopSequences;
            if (p.Seed.HasValue) acc.Seed = p.Seed;
            acc.ResponseFormatJson = p.ResponseFormatJson;
            acc.SearchEnabled = p.SearchEnabled;
            acc.ReturnCitations = p.ReturnCitations;
            if (p.SearchRecency != null) acc.SearchRecency = p.SearchRecency;
        }
        return acc;
    }

    // Derived copies
    public Settings WithModels(AppService service, List<string> models)
    {
        SetModels(service, models);
        return this;
    }

    public Settings WithProviderState(AppService service, string state)
    {
        SetProviderState(service, state);
        return this;
    }

    public List<SwarmMember> GetMembersForSwarms(IEnumerable<string> swarmIds) =>
        swarmIds.SelectMany(id => GetSwarmById(id)?.Members ?? new()).ToList();

    // Removal helpers
    public void RemoveAgent(string agentId)
    {
        Agents.RemoveAll(a => a.Id == agentId);
        foreach (var f in Flocks) f.AgentIds.RemoveAll(id => id == agentId);
        Prompts.RemoveAll(p => p.AgentId == agentId);
    }

    public void RemoveSystemPrompt(string id)
    {
        SystemPrompts.RemoveAll(sp => sp.Id == id);
        foreach (var a in Agents) { if (a.SystemPromptId == id) a.SystemPromptId = null; }
        foreach (var f in Flocks) { if (f.SystemPromptId == id) f.SystemPromptId = null; }
        foreach (var s in Swarms) { if (s.SystemPromptId == id) s.SystemPromptId = null; }
    }

    public void RemoveParameters(string id)
    {
        ParametersList.RemoveAll(p => p.Id == id);
        foreach (var a in Agents) a.ParamsIds.RemoveAll(pid => pid == id);
        foreach (var f in Flocks) f.ParamsIds.RemoveAll(pid => pid == id);
        foreach (var s in Swarms) s.ParamsIds.RemoveAll(pid => pid == id);
        foreach (var p in Providers.Values) p.ParametersIds.RemoveAll(pid => pid == id);
    }
}

public class ReportModel
{
    public string Id { get; } = Guid.NewGuid().ToString();
    public string ProviderId { get; set; } = "";
    public string Model { get; set; } = "";
    public string Type { get; set; } = "";
    public string SourceType { get; set; } = "";
    public string SourceName { get; set; } = "";
    public string? AgentId { get; set; }
    public string? EndpointId { get; set; }
    public string? AgentApiKey { get; set; }
    public List<string> ParamsIds { get; set; } = new();

    public AppService? Provider => AppService.FindById(ProviderId);
    public string DeduplicationKey => $"{ProviderId}:{Model}";
}

public static class ReportModelHelpers
{
    public static List<ReportModel> ExpandFlockToModels(Flock flock, Settings settings) =>
        flock.AgentIds.Select(id => settings.GetAgentById(id))
            .Where(a => a?.Provider != null && settings.IsProviderActive(a.Provider!))
            .Select(a => new ReportModel
            {
                ProviderId = a!.ProviderId,
                Model = settings.GetEffectiveModelForAgent(a),
                Type = "agent",
                SourceType = "flock",
                SourceName = flock.Name,
                AgentId = a.Id,
                EndpointId = a.EndpointId,
                AgentApiKey = settings.GetEffectiveApiKeyForAgent(a),
                ParamsIds = flock.ParamsIds.Concat(a.ParamsIds).ToList()
            }).ToList();

    public static List<ReportModel> ExpandSwarmToModels(Swarm swarm, Settings settings) =>
        swarm.Members.Where(m => m.Provider != null && settings.IsProviderActive(m.Provider!))
            .Select(m => new ReportModel
            {
                ProviderId = m.ProviderId,
                Model = m.Model,
                Type = "model",
                SourceType = "swarm",
                SourceName = swarm.Name,
                ParamsIds = swarm.ParamsIds.ToList()
            }).ToList();

    public static List<ReportModel> DeduplicateModels(List<ReportModel> models)
    {
        var seen = new HashSet<string>();
        return models.Where(m => seen.Add(m.DeduplicationKey)).ToList();
    }
}
