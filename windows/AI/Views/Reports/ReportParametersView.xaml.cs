using System.Windows;
using System.Windows.Controls;
using AI.Data;
using AI.ViewModels;

namespace AI.Views.Reports;

public partial class ReportParametersView : UserControl
{
    private AppViewModel Vm => App.ViewModel;

    public ReportParametersView()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        // Populate from existing advanced parameters if set
        var p = Vm.ReportAdvancedParameters;
        if (p == null) return;

        TempBox.Text = p.Temperature?.ToString() ?? "";
        MaxTokensBox.Text = p.MaxTokens?.ToString() ?? "";
        TopPBox.Text = p.TopP?.ToString() ?? "";
        TopKBox.Text = p.TopK?.ToString() ?? "";
        FreqPenBox.Text = p.FrequencyPenalty?.ToString() ?? "";
        PresencePenBox.Text = p.PresencePenalty?.ToString() ?? "";
        SeedBox.Text = p.Seed?.ToString() ?? "";
        SystemPromptBox.Text = p.SystemPrompt ?? "";
        JsonFormatCheck.IsChecked = p.ResponseFormatJson;
        SearchEnabledCheck.IsChecked = p.SearchEnabled;
        ReturnCitationsCheck.IsChecked = p.ReturnCitations;
    }

    private void ClearBtn_Click(object sender, RoutedEventArgs e)
    {
        TempBox.Text = "";
        MaxTokensBox.Text = "";
        TopPBox.Text = "";
        TopKBox.Text = "";
        FreqPenBox.Text = "";
        PresencePenBox.Text = "";
        SeedBox.Text = "";
        SystemPromptBox.Text = "";
        JsonFormatCheck.IsChecked = false;
        SearchEnabledCheck.IsChecked = false;
        ReturnCitationsCheck.IsChecked = false;

        Vm.ReportAdvancedParameters = null;
    }

    private void ApplyBtn_Click(object sender, RoutedEventArgs e)
    {
        var p = new AgentParameters
        {
            Temperature = float.TryParse(TempBox.Text, out var t) ? t : null,
            MaxTokens = int.TryParse(MaxTokensBox.Text, out var mt) ? mt : null,
            TopP = float.TryParse(TopPBox.Text, out var tp) ? tp : null,
            TopK = int.TryParse(TopKBox.Text, out var tk) ? tk : null,
            FrequencyPenalty = float.TryParse(FreqPenBox.Text, out var fp) ? fp : null,
            PresencePenalty = float.TryParse(PresencePenBox.Text, out var pp) ? pp : null,
            Seed = int.TryParse(SeedBox.Text, out var seed) ? seed : null,
            SystemPrompt = string.IsNullOrWhiteSpace(SystemPromptBox.Text) ? null : SystemPromptBox.Text.Trim(),
            ResponseFormatJson = JsonFormatCheck.IsChecked == true,
            SearchEnabled = SearchEnabledCheck.IsChecked == true,
            ReturnCitations = ReturnCitationsCheck.IsChecked == true
        };

        Vm.ReportAdvancedParameters = p;
    }
}
