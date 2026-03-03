using System.Text.Json;

namespace AI.Data;

public static class AnalysisModels
{
    private static readonly AnalysisRepository Repo = AnalysisRepository.Instance;

    public static async Task<string?> TestModel(AppService service, string apiKey, string model)
    {
        var parms = new AgentParameters();
        var result = service.ApiFormat switch
        {
            ApiFormat.Anthropic => await AnalysisProviders.AnalyzeWithClaude(service, apiKey, AnalysisRepository.TestPrompt, model, parms),
            ApiFormat.Google => await AnalysisProviders.AnalyzeWithGemini(service, apiKey, AnalysisRepository.TestPrompt, model, parms),
            _ => await AnalysisProviders.AnalyzeWithOpenAiCompatible(service, apiKey, AnalysisRepository.TestPrompt, model, parms)
        };
        return result.Error;
    }

    public static async Task<(string?, string?)> TestModelWithPrompt(AppService service, string apiKey, string model, string prompt)
    {
        var parms = new AgentParameters();
        var result = service.ApiFormat switch
        {
            ApiFormat.Anthropic => await AnalysisProviders.AnalyzeWithClaude(service, apiKey, prompt, model, parms),
            ApiFormat.Google => await AnalysisProviders.AnalyzeWithGemini(service, apiKey, prompt, model, parms),
            _ => await AnalysisProviders.AnalyzeWithOpenAiCompatible(service, apiKey, prompt, model, parms)
        };
        return (result.Analysis, result.Error);
    }

    public static async Task<List<string>> FetchModels(AppService service, string apiKey, string? customUrl = null)
    {
        return service.ApiFormat switch
        {
            ApiFormat.Google => await FetchGeminiModels(service, apiKey),
            ApiFormat.Anthropic => await FetchClaudeModels(service, apiKey),
            _ => await FetchModelsOpenAiCompatible(service, apiKey, customUrl)
        };
    }

    private static async Task<List<string>> FetchGeminiModels(AppService service, string apiKey)
    {
        var baseUrl = service.BaseUrl.EndsWith("/") ? service.BaseUrl : $"{service.BaseUrl}/";
        var url = $"{baseUrl}v1beta/models?key={Uri.EscapeDataString(apiKey)}";

        try
        {
            var dummyService = new AppService { Id = "gemini_temp", ApiFormatStr = "GOOGLE" };
            var (response, _) = await ApiClient.Instance.GetAsync<GeminiModelsResponse>(url, dummyService, apiKey);
            return response.Models?
                .Where(m => m.SupportedGenerationMethods?.Contains("generateContent") == true)
                .Select(m => m.Name?.Replace("models/", "") ?? "")
                .Where(n => !string.IsNullOrEmpty(n))
                .ToList() ?? new();
        }
        catch { return new(); }
    }

    private static async Task<List<string>> FetchClaudeModels(AppService service, string apiKey)
    {
        var baseUrl = service.BaseUrl.EndsWith("/") ? service.BaseUrl : $"{service.BaseUrl}/";
        var url = $"{baseUrl}v1/models";

        try
        {
            var (response, _) = await ApiClient.Instance.GetAsync<ClaudeModelsResponse>(url, service, apiKey);
            return response.Data?
                .Select(m => m.Id ?? "")
                .Where(id => id.StartsWith("claude"))
                .ToList() ?? new();
        }
        catch { return new(); }
    }

    private static async Task<List<string>> FetchModelsOpenAiCompatible(AppService service, string apiKey, string? customUrl)
    {
        if (service.ModelsPath == null)
            return service.HardcodedModels ?? new();

        var url = ApiClient.ModelsUrl(service, customUrl);
        if (string.IsNullOrEmpty(url)) return service.HardcodedModels ?? new();

        try
        {
            if (service.ModelListFormat == "array")
            {
                var (models, _) = await ApiClient.Instance.GetAsync<List<OpenAiModel>>(url, service, apiKey);
                return FilterModels(models.Select(m => m.Id ?? "").Where(id => !string.IsNullOrEmpty(id)).ToList(), service);
            }
            else
            {
                var (response, _) = await ApiClient.Instance.GetAsync<OpenAiModelsResponse>(url, service, apiKey);
                return FilterModels(response.Data?.Select(m => m.Id ?? "").Where(id => !string.IsNullOrEmpty(id)).ToList() ?? new(), service);
            }
        }
        catch
        {
            return service.HardcodedModels ?? new();
        }
    }

    private static List<string> FilterModels(List<string> models, AppService service)
    {
        if (service.ModelFilterRegex == null) return models.Order().ToList();
        return models.Where(m => service.ModelFilterRegex.IsMatch(m)).Order().ToList();
    }
}
