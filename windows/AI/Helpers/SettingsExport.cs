using System.Text.Json;
using System.Text.Json.Serialization;
using AI.Data;
using AI.ViewModels;
using Microsoft.Win32;

namespace AI.Helpers;

public static class SettingsExporter
{
    private const int CurrentVersion = 21;

    // Export models - v21 Android-compatible format

    public class ConfigExport
    {
        [JsonPropertyName("version")]
        public int Version { get; set; }

        [JsonPropertyName("providers")]
        public Dictionary<string, ProviderConfigExport> Providers { get; set; } = new();

        [JsonPropertyName("agents")]
        public List<AgentExport> Agents { get; set; } = new();

        [JsonPropertyName("flocks")]
        public List<FlockExport>? Flocks { get; set; }

        [JsonPropertyName("swarms")]
        public List<SwarmExport>? Swarms { get; set; }

        [JsonPropertyName("parameters")]
        public List<ParametersExport>? Parameters { get; set; }

        [JsonPropertyName("systemPrompts")]
        public List<SystemPromptExport>? SystemPrompts { get; set; }

        [JsonPropertyName("huggingFaceApiKey")]
        public string? HuggingFaceApiKey { get; set; }

        [JsonPropertyName("aiPrompts")]
        public List<PromptExport>? AiPrompts { get; set; }

        [JsonPropertyName("openRouterApiKey")]
        public string? OpenRouterApiKey { get; set; }

        [JsonPropertyName("providerStates")]
        public Dictionary<string, string>? ProviderStates { get; set; }
    }

    public class ProviderConfigExport
    {
        [JsonPropertyName("modelSource")]
        public string ModelSource { get; set; } = "API";

        [JsonPropertyName("models")]
        public List<string> Models { get; set; } = new();

        [JsonPropertyName("apiKey")]
        public string ApiKey { get; set; } = "";

        [JsonPropertyName("defaultModel")]
        public string? DefaultModel { get; set; }

        [JsonPropertyName("adminUrl")]
        public string? AdminUrl { get; set; }

        [JsonPropertyName("modelListUrl")]
        public string? ModelListUrl { get; set; }

        [JsonPropertyName("parametersIds")]
        public List<string>? ParametersIds { get; set; }

        [JsonPropertyName("displayName")]
        public string? DisplayName { get; set; }

        [JsonPropertyName("baseUrl")]
        public string? BaseUrl { get; set; }

        [JsonPropertyName("apiFormat")]
        public string? ApiFormat { get; set; }

        [JsonPropertyName("chatPath")]
        public string? ChatPath { get; set; }

        [JsonPropertyName("modelsPath")]
        public string? ModelsPath { get; set; }

        [JsonPropertyName("openRouterName")]
        public string? OpenRouterName { get; set; }
    }

    public class AgentExport
    {
        [JsonPropertyName("id")]
        public string Id { get; set; } = "";

        [JsonPropertyName("name")]
        public string Name { get; set; } = "";

        [JsonPropertyName("provider")]
        public string Provider { get; set; } = "";

        [JsonPropertyName("model")]
        public string Model { get; set; } = "";

        [JsonPropertyName("apiKey")]
        public string ApiKey { get; set; } = "";

        [JsonPropertyName("parametersIds")]
        public List<string>? ParametersIds { get; set; }

        [JsonPropertyName("endpointId")]
        public string? EndpointId { get; set; }

        [JsonPropertyName("systemPromptId")]
        public string? SystemPromptId { get; set; }
    }

    public class FlockExport
    {
        [JsonPropertyName("id")]
        public string Id { get; set; } = "";

        [JsonPropertyName("name")]
        public string Name { get; set; } = "";

        [JsonPropertyName("agentIds")]
        public List<string> AgentIds { get; set; } = new();

        [JsonPropertyName("parametersIds")]
        public List<string>? ParametersIds { get; set; }

        [JsonPropertyName("systemPromptId")]
        public string? SystemPromptId { get; set; }
    }

    public class SwarmMemberExport
    {
        [JsonPropertyName("provider")]
        public string Provider { get; set; } = "";

        [JsonPropertyName("model")]
        public string Model { get; set; } = "";
    }

    public class SwarmExport
    {
        [JsonPropertyName("id")]
        public string Id { get; set; } = "";

        [JsonPropertyName("name")]
        public string Name { get; set; } = "";

        [JsonPropertyName("members")]
        public List<SwarmMemberExport> Members { get; set; } = new();

        [JsonPropertyName("parametersIds")]
        public List<string>? ParametersIds { get; set; }

        [JsonPropertyName("systemPromptId")]
        public string? SystemPromptId { get; set; }
    }

    public class ParametersExport
    {
        [JsonPropertyName("id")]
        public string Id { get; set; } = "";

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
        public bool ReturnCitations { get; set; }

        [JsonPropertyName("searchRecency")]
        public string? SearchRecency { get; set; }
    }

    public class SystemPromptExport
    {
        [JsonPropertyName("id")]
        public string Id { get; set; } = "";

        [JsonPropertyName("name")]
        public string Name { get; set; } = "";

        [JsonPropertyName("prompt")]
        public string Prompt { get; set; } = "";
    }

    public class PromptExport
    {
        [JsonPropertyName("id")]
        public string Id { get; set; } = "";

        [JsonPropertyName("name")]
        public string Name { get; set; } = "";

        [JsonPropertyName("agentId")]
        public string AgentId { get; set; } = "";

        [JsonPropertyName("promptText")]
        public string PromptText { get; set; } = "";
    }

    // Export

    public static void ExportSettings(ViewModels.Settings aiSettings, GeneralSettings generalSettings)
    {
        var providers = new Dictionary<string, ProviderConfigExport>();
        foreach (var service in AppService.Entries)
        {
            var config = aiSettings.GetProvider(service);
            providers[service.Id] = new ProviderConfigExport
            {
                ModelSource = config.ModelSourceStr,
                Models = config.Models,
                ApiKey = config.ApiKey,
                DefaultModel = string.IsNullOrEmpty(config.Model) ? null : config.Model,
                AdminUrl = string.IsNullOrEmpty(config.AdminUrl) ? null : config.AdminUrl,
                ModelListUrl = string.IsNullOrEmpty(config.ModelListUrl) ? null : config.ModelListUrl,
                ParametersIds = config.ParametersIds.Count == 0 ? null : config.ParametersIds,
                DisplayName = service.DisplayName,
                BaseUrl = service.BaseUrl,
                ApiFormat = service.ApiFormatStr,
                ChatPath = service.ChatPath,
                ModelsPath = service.ModelsPath,
                OpenRouterName = service.OpenRouterName
            };
        }

        var agents = aiSettings.Agents.Select(a => new AgentExport
        {
            Id = a.Id, Name = a.Name, Provider = a.ProviderId, Model = a.Model, ApiKey = a.ApiKey,
            ParametersIds = a.ParamsIds.Count == 0 ? null : a.ParamsIds,
            EndpointId = a.EndpointId, SystemPromptId = a.SystemPromptId
        }).ToList();

        var flocks = aiSettings.Flocks.Select(f => new FlockExport
        {
            Id = f.Id, Name = f.Name, AgentIds = f.AgentIds,
            ParametersIds = f.ParamsIds.Count == 0 ? null : f.ParamsIds,
            SystemPromptId = f.SystemPromptId
        }).ToList();

        var swarms = aiSettings.Swarms.Select(s => new SwarmExport
        {
            Id = s.Id, Name = s.Name,
            Members = s.Members.Select(m => new SwarmMemberExport { Provider = m.ProviderId, Model = m.Model }).ToList(),
            ParametersIds = s.ParamsIds.Count == 0 ? null : s.ParamsIds,
            SystemPromptId = s.SystemPromptId
        }).ToList();

        var parameters = aiSettings.ParametersList.Select(p => new ParametersExport
        {
            Id = p.Id, Name = p.Name, Temperature = p.Temperature, MaxTokens = p.MaxTokens,
            TopP = p.TopP, TopK = p.TopK, FrequencyPenalty = p.FrequencyPenalty, PresencePenalty = p.PresencePenalty,
            SystemPrompt = p.SystemPrompt, StopSequences = p.StopSequences, Seed = p.Seed,
            ResponseFormatJson = p.ResponseFormatJson, SearchEnabled = p.SearchEnabled,
            ReturnCitations = p.ReturnCitations, SearchRecency = p.SearchRecency
        }).ToList();

        var sysPrompts = aiSettings.SystemPrompts.Select(sp => new SystemPromptExport
        {
            Id = sp.Id, Name = sp.Name, Prompt = sp.Prompt
        }).ToList();

        var aiPrompts = aiSettings.Prompts.Select(p => new PromptExport
        {
            Id = p.Id, Name = p.Name, AgentId = p.AgentId, PromptText = p.PromptText
        }).ToList();

        var export = new ConfigExport
        {
            Version = CurrentVersion,
            Providers = providers,
            Agents = agents,
            Flocks = flocks.Count == 0 ? null : flocks,
            Swarms = swarms.Count == 0 ? null : swarms,
            Parameters = parameters.Count == 0 ? null : parameters,
            SystemPrompts = sysPrompts.Count == 0 ? null : sysPrompts,
            HuggingFaceApiKey = string.IsNullOrEmpty(generalSettings.HuggingFaceApiKey) ? null : generalSettings.HuggingFaceApiKey,
            AiPrompts = aiPrompts.Count == 0 ? null : aiPrompts,
            OpenRouterApiKey = string.IsNullOrEmpty(generalSettings.OpenRouterApiKey) ? null : generalSettings.OpenRouterApiKey,
            ProviderStates = aiSettings.ProviderStates.Count == 0 ? null : aiSettings.ProviderStates
        };

        var options = new JsonSerializerOptions
        {
            WriteIndented = true,
            DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
        };
        var json = JsonSerializer.Serialize(export, options);

        var dialog = new SaveFileDialog
        {
            FileName = $"ai_config_{DateTime.Now:yyyyMMdd_HHmmss}.json",
            Filter = "JSON files (*.json)|*.json",
            DefaultExt = ".json"
        };

        if (dialog.ShowDialog() == true)
            File.WriteAllText(dialog.FileName, json);
    }

    // Import

    public static (ViewModels.Settings?, GeneralSettings?) ImportSettings()
    {
        var dialog = new OpenFileDialog
        {
            Filter = "JSON files (*.json)|*.json",
            DefaultExt = ".json"
        };

        if (dialog.ShowDialog() != true) return (null, null);

        try
        {
            var json = File.ReadAllText(dialog.FileName);
            var export = JsonSerializer.Deserialize<ConfigExport>(json);
            if (export == null || export.Version < 11 || export.Version > CurrentVersion)
                return (null, null);

            return ProcessImport(export);
        }
        catch { return (null, null); }
    }

    private static (ViewModels.Settings, GeneralSettings?) ProcessImport(ConfigExport export)
    {
        var settings = new ViewModels.Settings();

        settings.Agents = export.Agents.Where(ae => AppService.FindById(ae.Provider) != null)
            .Select(ae => new ViewModels.Agent
            {
                Id = ae.Id, Name = ae.Name, ProviderId = ae.Provider, Model = ae.Model, ApiKey = ae.ApiKey,
                EndpointId = ae.EndpointId, ParamsIds = ae.ParametersIds ?? new(), SystemPromptId = ae.SystemPromptId
            }).ToList();

        settings.Flocks = export.Flocks?.Select(fe => new ViewModels.Flock
        {
            Id = fe.Id, Name = fe.Name, AgentIds = fe.AgentIds,
            ParamsIds = fe.ParametersIds ?? new(), SystemPromptId = fe.SystemPromptId
        }).ToList() ?? new();

        settings.Swarms = export.Swarms?.Select(se => new ViewModels.Swarm
        {
            Id = se.Id, Name = se.Name,
            Members = se.Members.Where(me => AppService.FindById(me.Provider) != null)
                .Select(me => new ViewModels.SwarmMember { ProviderId = me.Provider, Model = me.Model }).ToList(),
            ParamsIds = se.ParametersIds ?? new(), SystemPromptId = se.SystemPromptId
        }).ToList() ?? new();

        settings.ParametersList = export.Parameters?.Select(pe => new ViewModels.Parameters
        {
            Id = pe.Id, Name = pe.Name, Temperature = pe.Temperature, MaxTokens = pe.MaxTokens,
            TopP = pe.TopP, TopK = pe.TopK, FrequencyPenalty = pe.FrequencyPenalty, PresencePenalty = pe.PresencePenalty,
            SystemPrompt = pe.SystemPrompt, StopSequences = pe.StopSequences, Seed = pe.Seed,
            ResponseFormatJson = pe.ResponseFormatJson, SearchEnabled = pe.SearchEnabled,
            ReturnCitations = pe.ReturnCitations, SearchRecency = pe.SearchRecency
        }).ToList() ?? new();

        settings.SystemPrompts = export.SystemPrompts?.Select(spe => new ViewModels.SystemPrompt
        {
            Id = spe.Id, Name = spe.Name, Prompt = spe.Prompt
        }).ToList() ?? new();

        settings.Prompts = export.AiPrompts?.Select(pe => new ViewModels.Prompt
        {
            Id = pe.Id, Name = pe.Name, AgentId = pe.AgentId, PromptText = pe.PromptText
        }).ToList() ?? new();

        if (export.ProviderStates != null)
            settings.ProviderStates = export.ProviderStates;

        foreach (var (providerId, pe) in export.Providers)
        {
            var service = AppService.FindById(providerId);
            if (service == null) continue;
            var importedSource = pe.ModelSource == "MANUAL" ? ViewModels.ModelSource.Manual : ViewModels.ModelSource.Api;
            var config = new ViewModels.ProviderConfig
            {
                ApiKey = pe.ApiKey,
                Model = pe.DefaultModel ?? "",
                ModelSource = importedSource,
                Models = pe.Models,
                AdminUrl = pe.AdminUrl ?? "",
                ModelListUrl = pe.ModelListUrl ?? "",
                ParametersIds = pe.ParametersIds ?? new()
            };
            settings.SetProvider(service, config);
        }

        GeneralSettings? general = null;
        if (export.HuggingFaceApiKey != null || export.OpenRouterApiKey != null)
        {
            general = new GeneralSettings
            {
                HuggingFaceApiKey = export.HuggingFaceApiKey ?? "",
                OpenRouterApiKey = export.OpenRouterApiKey ?? ""
            };
        }

        return (settings, general);
    }
}
