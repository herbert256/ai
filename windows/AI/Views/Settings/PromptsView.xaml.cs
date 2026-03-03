using System.Windows;
using System.Windows.Controls;
using AI.Data;
using AI.ViewModels;

namespace AI.Views.Settings;

public partial class PromptsView : UserControl
{
    private AppViewModel Vm => App.ViewModel;
    private Prompt? _editing;
    private bool _isNew;

    public PromptsView()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        RefreshAgentCombo();
        RefreshList();
    }

    private void RefreshList()
    {
        var prompts = Vm.AiSettings.Prompts.OrderBy(p => p.Name).Select(p =>
        {
            var agent = Vm.AiSettings.GetAgentById(p.AgentId);
            var preview = p.PromptText.Length > 50 ? p.PromptText[..50] + "..." : p.PromptText;
            return new PromptListItem
            {
                Id = p.Id,
                Name = p.Name,
                AgentName = agent?.Name ?? "No agent",
                Preview = preview
            };
        }).ToList();

        PromptList.ItemsSource = prompts;
        CountLabel.Text = $"{prompts.Count} prompt{(prompts.Count == 1 ? "" : "s")}";
    }

    private void RefreshAgentCombo()
    {
        AgentCombo.ItemsSource = Vm.AiSettings.Agents.Select(a => a.Name).ToList();
    }

    private void PromptList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (PromptList.SelectedItem is not PromptListItem item) return;
        _editing = Vm.AiSettings.GetPromptById(item.Id);
        _isNew = false;
        if (_editing == null) return;

        FormTitle.Text = $"Edit: {_editing.Name}";
        NameBox.Text = _editing.Name;
        PromptTextBox.Text = _editing.PromptText;
        SaveBtn.Content = "Save";

        var agentIdx = Vm.AiSettings.Agents.FindIndex(a => a.Id == _editing.AgentId);
        AgentCombo.SelectedIndex = agentIdx >= 0 ? agentIdx : -1;
    }

    private void NewBtn_Click(object sender, RoutedEventArgs e)
    {
        _editing = new Prompt { Name = "New Prompt" };
        _isNew = true;
        FormTitle.Text = "New Prompt";
        NameBox.Text = _editing.Name;
        PromptTextBox.Text = "";
        AgentCombo.SelectedIndex = -1;
        SaveBtn.Content = "Create";
    }

    private void SaveBtn_Click(object sender, RoutedEventArgs e)
    {
        if (_editing == null) return;

        var name = NameBox.Text.Trim();
        if (string.IsNullOrEmpty(name))
        {
            MessageBox.Show("Name is required.", "Validation",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var agentIdx = AgentCombo.SelectedIndex;
        if (agentIdx < 0 || agentIdx >= Vm.AiSettings.Agents.Count)
        {
            MessageBox.Show("Please select an agent.", "Validation",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        _editing.Name = name;
        _editing.AgentId = Vm.AiSettings.Agents[agentIdx].Id;
        _editing.PromptText = PromptTextBox.Text;

        if (_isNew)
        {
            Vm.AiSettings.Prompts.Add(_editing);
            _isNew = false;
        }

        Vm.UpdateSettings(Vm.AiSettings);
        RefreshList();
        FormTitle.Text = $"Edit: {_editing.Name}";
        SaveBtn.Content = "Save";
    }

    private void CancelBtn_Click(object sender, RoutedEventArgs e)
    {
        if (_isNew && _editing != null)
        {
            _editing = null;
            _isNew = false;
        }
        FormTitle.Text = "Select a prompt";
        NameBox.Text = "";
        PromptTextBox.Text = "";
        AgentCombo.SelectedIndex = -1;
        PromptList.SelectedItem = null;
    }

    private void DeleteBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button btn || btn.Tag is not string id) return;

        var prompt = Vm.AiSettings.GetPromptById(id);
        if (prompt == null) return;

        if (MessageBox.Show($"Delete prompt '{prompt.Name}'?", "Confirm",
            MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;

        Vm.AiSettings.Prompts.RemoveAll(p => p.Id == id);
        Vm.UpdateSettings(Vm.AiSettings);

        if (_editing?.Id == id)
        {
            _editing = null;
            FormTitle.Text = "Select a prompt";
            NameBox.Text = "";
            PromptTextBox.Text = "";
            AgentCombo.SelectedIndex = -1;
        }

        RefreshList();
    }
}

public class PromptListItem
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string AgentName { get; set; } = "";
    public string Preview { get; set; } = "";
}
