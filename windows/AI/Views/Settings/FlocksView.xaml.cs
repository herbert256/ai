using System.Windows;
using System.Windows.Controls;
using AI.ViewModels;

namespace AI.Views.Settings;

public partial class FlocksView : UserControl
{
    private AppViewModel Vm => App.ViewModel;
    private Flock? _editing;
    private bool _suppressSelectionChanged;

    public FlocksView()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        AgentCheckList.ItemsSource = Vm.AiSettings.Agents;
        RefreshList();
    }

    private void RefreshList() => FlockList.ItemsSource = Vm.AiSettings.Flocks.ToList();

    private void FlockList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        _editing = FlockList.SelectedItem as Flock;
        if (_editing == null) return;
        FormTitle.Text = $"Edit: {_editing.Name}";
        NameBox.Text = _editing.Name;

        _suppressSelectionChanged = true;
        AgentCheckList.SelectedItems.Clear();
        foreach (var agent in Vm.AiSettings.Agents)
        {
            if (_editing.AgentIds.Contains(agent.Id))
                AgentCheckList.SelectedItems.Add(agent);
        }
        _suppressSelectionChanged = false;
    }

    private void AgentCheckList_SelectionChanged(object sender, SelectionChangedEventArgs e) { }

    private void NewBtn_Click(object sender, RoutedEventArgs e)
    {
        _editing = new Flock { Name = "New Flock" };
        Vm.AiSettings.Flocks.Add(_editing);
        RefreshList();
        FlockList.SelectedItem = _editing;
    }

    private void SaveBtn_Click(object sender, RoutedEventArgs e)
    {
        if (_editing == null) return;
        _editing.Name = NameBox.Text.Trim();
        _editing.AgentIds = AgentCheckList.SelectedItems
            .Cast<AI.ViewModels.Agent>().Select(a => a.Id).ToList();
        Vm.UpdateSettings(Vm.AiSettings);
        RefreshList();
        FormTitle.Text = $"Edit: {_editing.Name}";
    }

    private void DeleteBtn_Click(object sender, RoutedEventArgs e)
    {
        if (_editing == null) return;
        if (MessageBox.Show($"Delete flock '{_editing.Name}'?", "Confirm",
            MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
        Vm.AiSettings.Flocks.RemoveAll(f => f.Id == _editing.Id);
        Vm.UpdateSettings(Vm.AiSettings);
        _editing = null;
        RefreshList();
        FormTitle.Text = "Select a flock";
    }
}
