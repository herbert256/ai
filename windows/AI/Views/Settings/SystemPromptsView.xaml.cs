using System.Windows;
using System.Windows.Controls;
using AI.Data;
using AI.ViewModels;

namespace AI.Views.Settings;

public partial class SystemPromptsView : UserControl
{
    private AppViewModel Vm => App.ViewModel;
    private SystemPrompt? _editing;
    private bool _isNew;

    public SystemPromptsView()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        RefreshList();
    }

    private void RefreshList()
    {
        var items = Vm.AiSettings.SystemPrompts.OrderBy(sp => sp.Name).Select(sp =>
        {
            var preview = sp.Prompt.Length > 80 ? sp.Prompt[..80] + "..." : sp.Prompt;
            return new SystemPromptListItem
            {
                Id = sp.Id,
                Name = sp.Name,
                Preview = preview
            };
        }).ToList();

        SystemPromptList.ItemsSource = items;
        CountLabel.Text = $"{items.Count} system prompt{(items.Count == 1 ? "" : "s")}";
    }

    private void SystemPromptList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (SystemPromptList.SelectedItem is not SystemPromptListItem item) return;
        _editing = Vm.AiSettings.GetSystemPromptById(item.Id);
        _isNew = false;
        if (_editing == null) return;

        FormTitle.Text = $"Edit: {_editing.Name}";
        NameBox.Text = _editing.Name;
        PromptBox.Text = _editing.Prompt;
        SaveBtn.Content = "Save";
        UpdateCharCount();
    }

    private void NewBtn_Click(object sender, RoutedEventArgs e)
    {
        _editing = new SystemPrompt { Name = "New System Prompt" };
        _isNew = true;
        FormTitle.Text = "New System Prompt";
        NameBox.Text = _editing.Name;
        PromptBox.Text = "";
        SaveBtn.Content = "Create";
        UpdateCharCount();
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

        _editing.Name = name;
        _editing.Prompt = PromptBox.Text;

        if (_isNew)
        {
            Vm.AiSettings.SystemPrompts.Add(_editing);
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
        FormTitle.Text = "Select a system prompt";
        NameBox.Text = "";
        PromptBox.Text = "";
        SystemPromptList.SelectedItem = null;
        UpdateCharCount();
    }

    private void DeleteBtn_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button btn || btn.Tag is not string id) return;

        var sp = Vm.AiSettings.GetSystemPromptById(id);
        if (sp == null) return;

        if (MessageBox.Show($"Delete system prompt '{sp.Name}'?\n\nReferences will be cleared from agents, flocks, and swarms.",
            "Confirm", MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;

        Vm.AiSettings.RemoveSystemPrompt(id);
        Vm.UpdateSettings(Vm.AiSettings);

        if (_editing?.Id == id)
        {
            _editing = null;
            FormTitle.Text = "Select a system prompt";
            NameBox.Text = "";
            PromptBox.Text = "";
            UpdateCharCount();
        }

        RefreshList();
    }

    private void PromptBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        UpdateCharCount();
    }

    private void UpdateCharCount()
    {
        var len = PromptBox.Text.Length;
        CharCountLabel.Text = $"{len:N0} character{(len == 1 ? "" : "s")}";
    }
}

public class SystemPromptListItem
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Preview { get; set; } = "";
}
