using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using AI.Data;
using AI.Helpers;

namespace AI.ViewModels;

public partial class AppViewModel : ObservableObject
{
    private readonly AnalysisRepository _repository = AnalysisRepository.Instance;
    private CancellationTokenSource? _reportCts;
    private const int ReportConcurrencyLimit = 4;

    // State
    [ObservableProperty] private bool _isLoading;
    [ObservableProperty] private string? _errorMessage;
    [ObservableProperty] private GeneralSettings _generalSettings = new();
    [ObservableProperty] private Settings _aiSettings = new();
    [ObservableProperty] private SidebarSection _selectedSection = SidebarSection.Hub;

    // Model loading
    [ObservableProperty] private HashSet<string> _loadingModelsFor = new();

    // Reports
    [ObservableProperty] private string _genericPromptTitle = "";
    [ObservableProperty] private string _genericPromptText = "";
    [ObservableProperty] private bool _showGenericAgentSelection;
    [ObservableProperty] private bool _showGenericReportsDialog;
    [ObservableProperty] private int _genericReportsProgress;
    [ObservableProperty] private int _genericReportsTotal;
    [ObservableProperty] private HashSet<string> _genericReportsSelectedAgents = new();
    [ObservableProperty] private Dictionary<string, AnalysisResponse> _genericReportsAgentResults = new();
    [ObservableProperty] private string? _currentReportId;
    [ObservableProperty] private AgentParameters? _reportAdvancedParameters;

    // Chat
    [ObservableProperty] private ChatParameters _chatParameters = new();
    [ObservableProperty] private DualChatConfig? _dualChatConfig;

    // Usage stats
    [ObservableProperty] private Dictionary<string, UsageStats> _usageStats = new();

    // Bootstrap
    public async Task Bootstrap()
    {
        ProviderRegistry.Instance.Initialize();
        ApiTracer.Instance.SetTracingEnabled(true);

        AiSettings = SettingsPreferences.LoadSettings();
        GeneralSettings = SettingsPreferences.LoadGeneralSettings();
        UsageStats = SettingsPreferences.LoadUsageStats();
    }

    // Settings Management
    public void UpdateSettings(Settings settings)
    {
        AiSettings = settings;
        Task.Run(() => SettingsPreferences.SaveSettings(settings));
    }

    public void UpdateGeneralSettings(GeneralSettings settings)
    {
        GeneralSettings = settings;
        Task.Run(() => SettingsPreferences.SaveGeneralSettings(settings));
    }

    public void UpdateProviderState(AppService service, string state)
    {
        AiSettings.SetProviderState(service, state);
        var s = AiSettings;
        Task.Run(() => SettingsPreferences.SaveSettings(s));
        OnPropertyChanged(nameof(AiSettings));
    }

    // Model Fetching
    public async Task FetchModels(AppService service)
    {
        var apiKey = AiSettings.GetApiKey(service);
        if (string.IsNullOrEmpty(apiKey)) return;

        LoadingModelsFor.Add(service.Id);
        OnPropertyChanged(nameof(LoadingModelsFor));

        try
        {
            var models = await AnalysisModels.FetchModels(service, apiKey);
            if (models.Count > 0)
            {
                AiSettings.SetModels(service, models);
                var s = AiSettings;
                Task.Run(() => SettingsPreferences.SaveSettings(s));
                OnPropertyChanged(nameof(AiSettings));
            }
        }
        finally
        {
            LoadingModelsFor.Remove(service.Id);
            OnPropertyChanged(nameof(LoadingModelsFor));
        }
    }

    // Model Testing
    public async Task<string?> TestAiModel(AppService service, string apiKey, string model)
    {
        var result = await Data.AnalysisModels.TestModel(service, apiKey, model);
        if (result == null)
            await UpdateUsageStats(service, model, 10, 2);
        return result;
    }

    // Chat Operations
    public async Task<ChatMessage> SendChatMessage(AppService provider, string apiKey, string model, List<ChatMessage> messages)
    {
        try
        {
            var response = await AnalysisChat.SendChatMessage(provider, apiKey, model, messages, ChatParameters);
            var inputTokens = messages.Sum(m => EstimateTokens(m.Content));
            var outputTokens = EstimateTokens(response);
            await UpdateUsageStats(provider, model, inputTokens, outputTokens);
            return new ChatMessage("assistant", response);
        }
        catch (Exception ex)
        {
            return new ChatMessage("assistant", $"Error: {ex.Message}");
        }
    }

    public IAsyncEnumerable<string> SendChatMessageStream(AppService provider, string apiKey, string model,
        List<ChatMessage> messages, string? customBaseUrl = null, CancellationToken ct = default)
    {
        return AnalysisStreaming.SendChatMessageStream(provider, apiKey, model, messages, ChatParameters, customBaseUrl, ct);
    }

    public async Task<string> SendDualChatMessage(AppService service, string apiKey, string model,
        List<ChatMessage> messages, ChatParameters parms)
    {
        var response = await AnalysisChat.SendChatMessage(service, apiKey, model, messages, parms);
        var inputTokens = messages.Sum(m => EstimateTokens(m.Content));
        await UpdateUsageStats(service, model, inputTokens, EstimateTokens(response));
        return response;
    }

    public void RecordChatStatistics(AppService provider, string model, int inputTokens, int outputTokens)
    {
        _ = UpdateUsageStats(provider, model, inputTokens, outputTokens);
    }

    // Report Generation
    public void ShowGenericAgentSelection(string title, string prompt)
    {
        GenericPromptTitle = title;
        GenericPromptText = prompt;
        ShowGenericAgentSelection = true;
        ShowGenericReportsDialog = false;
        GenericReportsProgress = 0;
        GenericReportsTotal = 0;
        GenericReportsSelectedAgents = new();
        GenericReportsAgentResults = new();
        CurrentReportId = null;
    }

    public void DismissGenericAgentSelection() => ShowGenericAgentSelection = false;

    public async Task GenerateGenericReports(
        HashSet<string> selectedAgentIds, HashSet<string>? selectedSwarmIds = null,
        HashSet<string>? directModelIds = null, List<string>? parametersIds = null)
    {
        _reportCts?.Cancel();
        _reportCts = new CancellationTokenSource();
        var ct = _reportCts.Token;

        var prompt = GenericPromptText;
        var title = GenericPromptTitle;
        var mergedParams = parametersIds != null ? AiSettings.MergeParameters(parametersIds) : null;
        var overrideParams = mergedParams ?? ReportAdvancedParameters;

        // Build report tasks
        var tasks = new List<(string id, Agent agent, AgentParameters parms)>();

        // Agent tasks
        foreach (var agentId in selectedAgentIds)
        {
            var agent = AiSettings.GetAgentById(agentId);
            if (agent == null) continue;
            var effectiveAgent = new Agent
            {
                Id = agent.Id, Name = agent.Name, ProviderId = agent.ProviderId,
                Model = AiSettings.GetEffectiveModelForAgent(agent),
                ApiKey = AiSettings.GetEffectiveApiKeyForAgent(agent),
                EndpointId = agent.EndpointId, ParamsIds = agent.ParamsIds, SystemPromptId = agent.SystemPromptId
            };
            var parms = AiSettings.ResolveAgentParameters(agent);
            tasks.Add((agent.Id, effectiveAgent, parms));
        }

        // Swarm members
        if (selectedSwarmIds != null)
        {
            foreach (var member in AiSettings.GetMembersForSwarms(selectedSwarmIds))
            {
                if (member.Provider == null) continue;
                var syntheticId = $"swarm:{member.ProviderId}:{member.Model}";
                var syntheticAgent = new Agent
                {
                    Id = syntheticId, Name = $"{member.Provider.DisplayName} / {member.Model}",
                    ProviderId = member.ProviderId, Model = member.Model,
                    ApiKey = AiSettings.GetApiKey(member.Provider)
                };
                tasks.Add((syntheticId, syntheticAgent, new AgentParameters()));
            }
        }

        ShowGenericAgentSelection = false;
        ShowGenericReportsDialog = true;
        GenericReportsProgress = 0;
        GenericReportsTotal = tasks.Count;
        GenericReportsSelectedAgents = new(tasks.Select(t => t.id));
        GenericReportsAgentResults = new();

        var reportId = Guid.NewGuid().ToString();
        var report = new StoredReport(reportId, string.IsNullOrEmpty(title) ? "AI Report" : title, prompt, DateTimeOffset.UtcNow);
        ReportStorage.Instance.Save(report);
        CurrentReportId = reportId;
        ApiTracer.Instance.SetCurrentReportId(reportId);

        // Run with concurrency limit
        var semaphore = new SemaphoreSlim(ReportConcurrencyLimit);
        var runTasks = tasks.Select(async task =>
        {
            if (ct.IsCancellationRequested) return;
            await semaphore.WaitAsync(ct);
            try
            {
                var finalOverride = _repository.MergeParameters(task.parms, overrideParams);
                var response = await AnalysisProviders.AnalyzeWithAgent(task.agent, "", prompt, AiSettings, finalOverride);

                var result = StoredAnalysisResult.FromResponse(response);
                var loaded = ReportStorage.Instance.Load(reportId);
                if (loaded != null)
                {
                    loaded.Results.Add(result);
                    ReportStorage.Instance.Save(loaded);
                }

                if (response.TokenUsage != null && task.agent.Provider != null)
                    await UpdateUsageStats(task.agent.Provider, task.agent.Model, response.TokenUsage.InputTokens, response.TokenUsage.OutputTokens);

                System.Windows.Application.Current?.Dispatcher.Invoke(() =>
                {
                    GenericReportsProgress++;
                    GenericReportsAgentResults[task.id] = response;
                    OnPropertyChanged(nameof(GenericReportsProgress));
                    OnPropertyChanged(nameof(GenericReportsAgentResults));
                });
            }
            finally { semaphore.Release(); }
        });

        await Task.WhenAll(runTasks);
        ApiTracer.Instance.SetCurrentReportId(null);
    }

    public void StopGenericReports()
    {
        _reportCts?.Cancel();

        foreach (var id in GenericReportsSelectedAgents.Where(id => !GenericReportsAgentResults.ContainsKey(id)))
        {
            var service = AppService.Entries.FirstOrDefault() ?? AppService.FindById("OPENAI")!;
            GenericReportsAgentResults[id] = new AnalysisResponse(service, analysis: "Not ready");
        }

        GenericReportsProgress = GenericReportsTotal;
        OnPropertyChanged(nameof(GenericReportsProgress));
        OnPropertyChanged(nameof(GenericReportsAgentResults));
        ApiTracer.Instance.SetCurrentReportId(null);
    }

    public void DismissGenericReportsDialog()
    {
        ApiTracer.Instance.SetCurrentReportId(null);
        ShowGenericReportsDialog = false;
        GenericPromptTitle = "";
        GenericPromptText = "";
        GenericReportsProgress = 0;
        GenericReportsTotal = 0;
        GenericReportsSelectedAgents = new();
        GenericReportsAgentResults = new();
        CurrentReportId = null;
        ReportAdvancedParameters = null;
    }

    // Usage Statistics
    private Task UpdateUsageStats(AppService provider, string model, int inputTokens, int outputTokens)
    {
        var key = $"{provider.Id}::{model}";
        if (!UsageStats.TryGetValue(key, out var stats))
            stats = new UsageStats(provider.Id, model);
        stats.CallCount++;
        stats.InputTokens += inputTokens;
        stats.OutputTokens += outputTokens;
        UsageStats[key] = stats;
        OnPropertyChanged(nameof(UsageStats));

        var all = UsageStats;
        return Task.Run(() => SettingsPreferences.SaveUsageStats(all));
    }

    // Trace
    public void ClearTraces() => ApiTracer.Instance.ClearTraces();

    // Token estimation
    public static int EstimateTokens(string text) => Math.Max(text.Length / 4, 1);
}
