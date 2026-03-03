using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using AI.Data;
using AI.Helpers;
using AI.ViewModels;

namespace AI.Views.Admin;

public partial class DeveloperView : UserControl
{
    private AppViewModel Vm => App.ViewModel;

    public DeveloperView()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        ProviderCombo.ItemsSource = AppService.Entries.Select(s => s.DisplayName).ToList();
        if (AppService.Entries.Count > 0)
            ProviderCombo.SelectedIndex = 0;
    }

    private AppService? GetSelectedService()
    {
        var idx = ProviderCombo.SelectedIndex;
        return idx >= 0 && idx < AppService.Entries.Count ? AppService.Entries[idx] : null;
    }

    private void ProviderCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        var service = GetSelectedService();
        if (service == null) return;

        // Load API key
        ApiKeyBox.Text = Vm.AiSettings.GetApiKey(service);

        // Load endpoint
        EndpointBox.Text = Vm.AiSettings.GetEffectiveEndpointUrl(service);

        // Load model
        var model = Vm.AiSettings.GetModel(service);
        if (string.IsNullOrEmpty(model)) model = service.DefaultModel;
        ModelBox.Text = model;

        // Populate model combo
        var models = Vm.AiSettings.GetModels(service);
        ModelCombo.ItemsSource = models;
        var selectedIdx = models.IndexOf(model);
        ModelCombo.SelectedIndex = selectedIdx >= 0 ? selectedIdx : -1;
    }

    private void ModelCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ModelCombo.SelectedItem is string model)
            ModelBox.Text = model;
    }

    private async void SendBtn_Click(object sender, RoutedEventArgs e)
    {
        var service = GetSelectedService();
        if (service == null) return;

        var apiKey = ApiKeyBox.Text.Trim();
        var model = ModelBox.Text.Trim();
        var prompt = PromptBox.Text.Trim();

        if (string.IsNullOrEmpty(apiKey))
        {
            SendStatus.Text = "API key is required.";
            SendStatus.Foreground = (SolidColorBrush)FindResource("ErrorBrush");
            return;
        }
        if (string.IsNullOrEmpty(model))
        {
            SendStatus.Text = "Model is required.";
            SendStatus.Foreground = (SolidColorBrush)FindResource("ErrorBrush");
            return;
        }
        if (string.IsNullOrEmpty(prompt))
        {
            SendStatus.Text = "Prompt is required.";
            SendStatus.Foreground = (SolidColorBrush)FindResource("ErrorBrush");
            return;
        }

        SendBtn.IsEnabled = false;
        SendStatus.Text = "Sending request...";
        SendStatus.Foreground = (SolidColorBrush)FindResource("TextSecondaryBrush");
        ResultBox.Text = "";
        TokensLabel.Text = "";

        try
        {
            var parms = new AgentParameters
            {
                SystemPrompt = string.IsNullOrWhiteSpace(SystemPromptBox.Text) ? null : SystemPromptBox.Text.Trim()
            };
            if (float.TryParse(TemperatureBox.Text, out var temp))
                parms.Temperature = temp;
            if (int.TryParse(MaxTokensBox.Text, out var maxTokens))
                parms.MaxTokens = maxTokens;

            var agent = new Agent
            {
                Name = "Developer Test",
                ProviderId = service.Id,
                Model = model,
                ApiKey = apiKey
            };

            var customBaseUrl = EndpointBox.Text.Trim();
            var response = await AnalysisProviders.AnalyzeWithAgent(agent, "", prompt, Vm.AiSettings, parms);

            if (response.IsSuccess)
            {
                ResultBox.Text = response.Analysis ?? "";
                SendStatus.Text = "Request completed successfully.";
                SendStatus.Foreground = (SolidColorBrush)FindResource("SuccessBrush");

                if (response.TokenUsage != null)
                {
                    TokensLabel.Text = $"In: {response.TokenUsage.InputTokens:N0}  Out: {response.TokenUsage.OutputTokens:N0}";
                }
            }
            else
            {
                ResultBox.Text = response.Error ?? "Unknown error";
                SendStatus.Text = $"Request failed.{(response.HttpStatusCode.HasValue ? $" HTTP {response.HttpStatusCode}" : "")}";
                SendStatus.Foreground = (SolidColorBrush)FindResource("ErrorBrush");
            }
        }
        catch (Exception ex)
        {
            ResultBox.Text = ex.ToString();
            SendStatus.Text = "Request failed with exception.";
            SendStatus.Foreground = (SolidColorBrush)FindResource("ErrorBrush");
        }
        finally
        {
            SendBtn.IsEnabled = true;
        }
    }

    private void CopyResultBtn_Click(object sender, RoutedEventArgs e)
    {
        if (!string.IsNullOrEmpty(ResultBox.Text))
            Clipboard.SetText(ResultBox.Text);
    }
}
