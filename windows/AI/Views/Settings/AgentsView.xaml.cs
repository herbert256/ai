using System.Windows;
using System.Windows.Controls;
using AI.Data;
using AI.ViewModels;

namespace AI.Views.Settings;

public partial class AgentsView : UserControl
{
    private AppViewModel Vm => App.ViewModel;
    private Agent? _editing;

    public AgentsView()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        ProviderCombo.ItemsSource = AppService.Entries.Select(s => s.DisplayName).ToList();
        RefreshList();
    }

    private void RefreshList() => AgentList.ItemsSource = Vm.AiSettings.Agents.ToList();

    private AppService? GetSelectedProvider()
    {
        var idx = ProviderCombo.SelectedIndex;
        return idx >= 0 && idx < AppService.Entries.Count ? AppService.Entries[idx] : null;
    }

    private void ProviderCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        var svc = GetSelectedProvider();
        if (svc == null) return;
        var models = Vm.AiSettings.GetModels(svc);
        ModelCombo.ItemsSource = models.Count > 0 ? models : new List<string> { svc.DefaultModel };
        ModelCombo.Text = svc.DefaultModel;
    }

    private void AgentList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        _editing = AgentList.SelectedItem as Agent;
        if (_editing == null) return;
        FormTitle.Text = $"Edit: {_editing.Name}";
        NameBox.Text = _editing.Name;
        ApiKeyBox.Text = _editing.ApiKey;

        var provIdx = AppService.Entries.FindIndex(s => s.Id == _editing.ProviderId);
        ProviderCombo.SelectedIndex = provIdx >= 0 ? provIdx : 0;
        ModelCombo.Text = _editing.Model;
    }

    private void NewBtn_Click(object sender, RoutedEventArgs e)
    {
        _editing = new Agent { Name = "New Agent" };
        Vm.AiSettings.Agents.Add(_editing);
        RefreshList();
        AgentList.SelectedItem = _editing;
    }

    private void SaveBtn_Click(object sender, RoutedEventArgs e)
    {
        if (_editing == null) return;
        _editing.Name = NameBox.Text.Trim();
        _editing.ApiKey = ApiKeyBox.Text.Trim();
        _editing.Model = ModelCombo.Text.Trim();
        var svc = GetSelectedProvider();
        if (svc != null) _editing.ProviderId = svc.Id;
        Vm.UpdateSettings(Vm.AiSettings);
        RefreshList();
        FormTitle.Text = $"Edit: {_editing.Name}";
    }

    private void DeleteBtn_Click(object sender, RoutedEventArgs e)
    {
        if (_editing == null) return;
        if (MessageBox.Show($"Delete agent '{_editing.Name}'?", "Confirm",
            MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
        Vm.AiSettings.RemoveAgent(_editing.Id);
        Vm.UpdateSettings(Vm.AiSettings);
        _editing = null;
        RefreshList();
        FormTitle.Text = "Select an agent";
    }
}
