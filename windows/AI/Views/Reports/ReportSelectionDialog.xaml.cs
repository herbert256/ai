using System.Windows;
using System.Windows.Controls;
using AI.Data;
using AI.ViewModels;

namespace AI.Views.Reports;

public partial class ReportSelectionDialog : UserControl
{
    private AppViewModel Vm => App.ViewModel;
    private string _searchFilter = "";

    // Selection state
    public HashSet<string> SelectedAgentIds { get; } = new();
    public HashSet<string> SelectedSwarmIds { get; } = new();
    public HashSet<string> DirectModelIds { get; } = new();
    public List<string> ParametersIds { get; } = new();

    // Done event
    public event EventHandler? Done;

    // View model items
    private List<SelectionItem> _allAgents = new();
    private List<SelectionItem> _allFlocks = new();
    private List<SelectionItem> _allSwarms = new();
    private List<SelectionItem> _allModels = new();
    private List<SelectionItem> _allParams = new();

    public ReportSelectionDialog()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        BuildAgentItems();
        BuildFlockItems();
        BuildSwarmItems();
        BuildModelItems();
        BuildParameterItems();
        ApplyFilter();
        UpdateSelectionCount();
    }

    private void BuildAgentItems()
    {
        _allAgents = Vm.AiSettings.Agents.Select(a =>
        {
            var provider = a.Provider?.DisplayName ?? "Unknown";
            var model = Vm.AiSettings.GetEffectiveModelForAgent(a);
            return new SelectionItem
            {
                Id = a.Id,
                Name = a.Name,
                Detail = $"{provider} / {model}",
                IsSelected = SelectedAgentIds.Contains(a.Id)
            };
        }).ToList();
    }

    private void BuildFlockItems()
    {
        _allFlocks = Vm.AiSettings.Flocks.Select(f => new SelectionItem
        {
            Id = f.Id,
            Name = f.Name,
            Detail = $"{f.AgentIds.Count} agents",
            IsSelected = false
        }).ToList();
    }

    private void BuildSwarmItems()
    {
        _allSwarms = Vm.AiSettings.Swarms.Select(s => new SelectionItem
        {
            Id = s.Id,
            Name = s.Name,
            Detail = $"{s.Members.Count} members",
            IsSelected = SelectedSwarmIds.Contains(s.Id)
        }).ToList();
    }

    private void BuildModelItems()
    {
        _allModels = new();
        foreach (var service in AppService.Entries)
        {
            var models = Vm.AiSettings.GetProvider(service).Models;
            foreach (var model in models)
            {
                var modelKey = $"{service.Id}:{model}";
                _allModels.Add(new SelectionItem
                {
                    Id = modelKey,
                    Name = model,
                    Detail = service.DisplayName,
                    IsSelected = DirectModelIds.Contains(modelKey)
                });
            }
        }
    }

    private void BuildParameterItems()
    {
        _allParams = Vm.AiSettings.ParametersList.Select(p =>
        {
            var parts = new List<string>();
            if (p.Temperature.HasValue) parts.Add($"temp={p.Temperature}");
            if (p.MaxTokens.HasValue) parts.Add($"max={p.MaxTokens}");
            if (p.TopP.HasValue) parts.Add($"topP={p.TopP}");
            if (p.SearchEnabled) parts.Add("search");
            var summary = parts.Count > 0 ? string.Join(", ", parts) : "No overrides";
            return new SelectionItem
            {
                Id = p.Id,
                Name = p.Name,
                Detail = summary,
                IsSelected = ParametersIds.Contains(p.Id)
            };
        }).ToList();
    }

    private void ApplyFilter()
    {
        var filter = _searchFilter.ToLowerInvariant();
        AgentList.ItemsSource = FilterItems(_allAgents, filter);
        FlockList.ItemsSource = FilterItems(_allFlocks, filter);
        SwarmList.ItemsSource = FilterItems(_allSwarms, filter);
        ParametersList.ItemsSource = FilterItems(_allParams, filter);
        BuildModelsPanel(filter);
    }

    private List<SelectionItem> FilterItems(List<SelectionItem> items, string filter) =>
        string.IsNullOrEmpty(filter) ? items :
        items.Where(i => i.Name.Contains(filter, StringComparison.OrdinalIgnoreCase) ||
                         i.Detail.Contains(filter, StringComparison.OrdinalIgnoreCase)).ToList();

    private void BuildModelsPanel(string filter)
    {
        ModelsPanel.Children.Clear();
        var grouped = _allModels
            .Where(m => string.IsNullOrEmpty(filter) ||
                        m.Name.Contains(filter, StringComparison.OrdinalIgnoreCase) ||
                        m.Detail.Contains(filter, StringComparison.OrdinalIgnoreCase))
            .GroupBy(m => m.Detail);

        foreach (var group in grouped)
        {
            var header = new TextBlock
            {
                Text = group.Key,
                FontWeight = FontWeights.SemiBold,
                Foreground = (System.Windows.Media.Brush)FindResource("PrimaryBrush"),
                Margin = new Thickness(0, 8, 0, 4)
            };
            ModelsPanel.Children.Add(header);

            foreach (var item in group)
            {
                var cb = new CheckBox
                {
                    Content = item.Name,
                    IsChecked = item.IsSelected,
                    Margin = new Thickness(8, 2, 0, 2),
                    Tag = item
                };
                cb.Checked += ModelCheck_Changed;
                cb.Unchecked += ModelCheck_Changed;
                ModelsPanel.Children.Add(cb);
            }
        }
    }

    private void SearchBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        _searchFilter = SearchBox.Text.Trim();
        ApplyFilter();
    }

    private void AgentCheck_Changed(object sender, RoutedEventArgs e)
    {
        SyncSelectionFromItems(_allAgents, SelectedAgentIds);
        // When an agent is selected, also select all agents in any selected flock
        SyncFlockAgents();
        UpdateSelectionCount();
    }

    private void FlockCheck_Changed(object sender, RoutedEventArgs e)
    {
        SyncFlockAgents();
        UpdateSelectionCount();
    }

    private void SwarmCheck_Changed(object sender, RoutedEventArgs e)
    {
        SyncSelectionFromItems(_allSwarms, SelectedSwarmIds);
        UpdateSelectionCount();
    }

    private void ModelCheck_Changed(object sender, RoutedEventArgs e)
    {
        if (sender is CheckBox cb && cb.Tag is SelectionItem item)
        {
            item.IsSelected = cb.IsChecked == true;
            SyncSelectionFromItems(_allModels, DirectModelIds);
            UpdateSelectionCount();
        }
    }

    private void ParamCheck_Changed(object sender, RoutedEventArgs e)
    {
        ParametersIds.Clear();
        foreach (var item in _allParams.Where(i => i.IsSelected))
            ParametersIds.Add(item.Id);
    }

    private void SyncFlockAgents()
    {
        // Collect agent IDs from selected flocks
        SelectedAgentIds.Clear();
        foreach (var item in _allAgents.Where(i => i.IsSelected))
            SelectedAgentIds.Add(item.Id);

        foreach (var flockItem in _allFlocks.Where(i => i.IsSelected))
        {
            var flock = Vm.AiSettings.GetFlockById(flockItem.Id);
            if (flock != null)
            {
                foreach (var agentId in flock.AgentIds)
                    SelectedAgentIds.Add(agentId);
            }
        }
    }

    private void SyncSelectionFromItems(List<SelectionItem> items, HashSet<string> target)
    {
        target.Clear();
        foreach (var item in items.Where(i => i.IsSelected))
            target.Add(item.Id);
    }

    private void UpdateSelectionCount()
    {
        var total = SelectedAgentIds.Count + SelectedSwarmIds.Count + DirectModelIds.Count;
        SelectionCount.Text = $"{total} model{(total == 1 ? "" : "s")} selected";
    }

    private void ClearAllBtn_Click(object sender, RoutedEventArgs e)
    {
        SelectedAgentIds.Clear();
        SelectedSwarmIds.Clear();
        DirectModelIds.Clear();
        ParametersIds.Clear();

        foreach (var item in _allAgents) item.IsSelected = false;
        foreach (var item in _allFlocks) item.IsSelected = false;
        foreach (var item in _allSwarms) item.IsSelected = false;
        foreach (var item in _allModels) item.IsSelected = false;
        foreach (var item in _allParams) item.IsSelected = false;

        ApplyFilter();
        UpdateSelectionCount();
    }

    private void DoneBtn_Click(object sender, RoutedEventArgs e)
    {
        Done?.Invoke(this, EventArgs.Empty);
    }
}

public class SelectionItem
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Detail { get; set; } = "";
    public bool IsSelected { get; set; }
}
