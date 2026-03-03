using System.Windows;
using System.Windows.Controls;
using AI.ViewModels;

namespace AI.Views.Settings;

public partial class ParametersView : UserControl
{
    private AppViewModel Vm => App.ViewModel;
    private Parameters? _editing;

    public ParametersView()
    {
        InitializeComponent();
        Loaded += (_, _) => RefreshList();
    }

    private void RefreshList() => ParamList.ItemsSource = Vm.AiSettings.ParametersList.ToList();

    private void ParamList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        _editing = ParamList.SelectedItem as Parameters;
        if (_editing == null) return;
        FormTitle.Text = $"Edit: {_editing.Name}";
        NameBox.Text = _editing.Name;
        TempBox.Text = _editing.Temperature?.ToString() ?? "";
        MaxTokensBox.Text = _editing.MaxTokens?.ToString() ?? "";
        TopPBox.Text = _editing.TopP?.ToString() ?? "";
        TopKBox.Text = _editing.TopK?.ToString() ?? "";
        FreqPenBox.Text = _editing.FrequencyPenalty?.ToString() ?? "";
        PresencePenBox.Text = _editing.PresencePenalty?.ToString() ?? "";
        JsonModeCheck.IsChecked = _editing.ResponseFormatJson;
        SearchCheck.IsChecked = _editing.SearchEnabled;
    }

    private void NewBtn_Click(object sender, RoutedEventArgs e)
    {
        _editing = new Parameters { Name = "New Preset" };
        Vm.AiSettings.ParametersList.Add(_editing);
        RefreshList();
        ParamList.SelectedItem = _editing;
    }

    private void SaveBtn_Click(object sender, RoutedEventArgs e)
    {
        if (_editing == null) return;
        _editing.Name = NameBox.Text.Trim();
        _editing.Temperature = float.TryParse(TempBox.Text, out var t) ? t : null;
        _editing.MaxTokens = int.TryParse(MaxTokensBox.Text, out var mt) ? mt : null;
        _editing.TopP = float.TryParse(TopPBox.Text, out var tp) ? tp : null;
        _editing.TopK = int.TryParse(TopKBox.Text, out var tk) ? tk : null;
        _editing.FrequencyPenalty = float.TryParse(FreqPenBox.Text, out var fp) ? fp : null;
        _editing.PresencePenalty = float.TryParse(PresencePenBox.Text, out var pp) ? pp : null;
        _editing.ResponseFormatJson = JsonModeCheck.IsChecked == true;
        _editing.SearchEnabled = SearchCheck.IsChecked == true;
        Vm.UpdateSettings(Vm.AiSettings);
        RefreshList();
        FormTitle.Text = $"Edit: {_editing.Name}";
    }

    private void DeleteBtn_Click(object sender, RoutedEventArgs e)
    {
        if (_editing == null) return;
        if (MessageBox.Show($"Delete preset '{_editing.Name}'?", "Confirm",
            MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
        Vm.AiSettings.RemoveParameters(_editing.Id);
        Vm.UpdateSettings(Vm.AiSettings);
        _editing = null;
        RefreshList();
        FormTitle.Text = "Select a preset";
    }
}
