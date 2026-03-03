using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using AI.Data;
using AI.Helpers;
using AI.ViewModels;

namespace AI.Views.Admin;

public partial class HousekeepingView : UserControl
{
    private AppViewModel Vm => App.ViewModel;

    public HousekeepingView()
    {
        InitializeComponent();
    }

    // Refresh Models

    private async void RefreshModelsBtn_Click(object sender, RoutedEventArgs e)
    {
        RefreshModelsBtn.IsEnabled = false;
        var services = AppService.Entries.Where(s => !string.IsNullOrEmpty(Vm.AiSettings.GetApiKey(s))).ToList();
        RefreshModelsStatus.Text = $"Refreshing models for {services.Count} providers...";
        RefreshModelsStatus.Foreground = (SolidColorBrush)FindResource("TextSecondaryBrush");

        int success = 0, failed = 0;
        foreach (var service in services)
        {
            try
            {
                RefreshModelsStatus.Text = $"Fetching models for {service.DisplayName}...";
                await Vm.FetchModels(service);
                success++;
            }
            catch
            {
                failed++;
            }
        }

        RefreshModelsStatus.Text = $"Done. {success} succeeded, {failed} failed.";
        RefreshModelsStatus.Foreground = failed == 0
            ? (SolidColorBrush)FindResource("SuccessBrush")
            : (SolidColorBrush)FindResource("ErrorBrush");
        RefreshModelsBtn.IsEnabled = true;
    }

    // Test Providers

    private async void TestProvidersBtn_Click(object sender, RoutedEventArgs e)
    {
        TestProvidersBtn.IsEnabled = false;
        TestResultsPanel.Children.Clear();
        var activeServices = Vm.AiSettings.GetActiveServices();
        TestProvidersStatus.Text = $"Testing {activeServices.Count} providers...";
        TestProvidersStatus.Foreground = (SolidColorBrush)FindResource("TextSecondaryBrush");

        foreach (var service in activeServices)
        {
            var apiKey = Vm.AiSettings.GetApiKey(service);
            var model = Vm.AiSettings.GetModel(service);
            if (string.IsNullOrEmpty(model)) model = service.DefaultModel;

            var row = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 2, 0, 2) };
            var nameText = new TextBlock
            {
                Text = service.DisplayName,
                Width = 200,
                VerticalAlignment = VerticalAlignment.Center
            };
            var statusText = new TextBlock
            {
                Text = "Testing...",
                Foreground = (SolidColorBrush)FindResource("TextSecondaryBrush"),
                VerticalAlignment = VerticalAlignment.Center
            };
            row.Children.Add(nameText);
            row.Children.Add(statusText);
            TestResultsPanel.Children.Add(row);

            try
            {
                var error = await Vm.TestAiModel(service, apiKey, model);
                if (error == null)
                {
                    statusText.Text = "OK";
                    statusText.Foreground = (SolidColorBrush)FindResource("SuccessBrush");
                }
                else
                {
                    statusText.Text = $"Error: {error}";
                    statusText.Foreground = (SolidColorBrush)FindResource("ErrorBrush");
                }
            }
            catch (Exception ex)
            {
                statusText.Text = $"Error: {ex.Message}";
                statusText.Foreground = (SolidColorBrush)FindResource("ErrorBrush");
            }
        }

        TestProvidersStatus.Text = "Testing complete.";
        TestProvidersStatus.Foreground = (SolidColorBrush)FindResource("SuccessBrush");
        TestProvidersBtn.IsEnabled = true;
    }

    // Generate Default Agents

    private void GenerateAgentsBtn_Click(object sender, RoutedEventArgs e)
    {
        var activeServices = Vm.AiSettings.GetActiveServices();
        var settings = Vm.AiSettings;
        int created = 0;

        foreach (var service in activeServices)
        {
            var model = settings.GetModel(service);
            if (string.IsNullOrEmpty(model)) model = service.DefaultModel;
            if (string.IsNullOrEmpty(model)) continue;

            // Skip if an agent already exists for this provider
            if (settings.Agents.Any(a => a.ProviderId == service.Id)) continue;

            settings.Agents.Add(new Agent
            {
                Name = $"{service.DisplayName} Default",
                ProviderId = service.Id,
                Model = model
            });
            created++;
        }

        Vm.UpdateSettings(settings);
        GenerateAgentsStatus.Text = created > 0
            ? $"Created {created} agent(s)."
            : "No new agents needed. All active providers already have agents.";
        GenerateAgentsStatus.Foreground = (SolidColorBrush)FindResource("SuccessBrush");
    }

    // Export / Import

    private void ExportBtn_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            SettingsExporter.ExportSettings(Vm.AiSettings, Vm.GeneralSettings);
            ExportImportStatus.Text = "Configuration exported.";
            ExportImportStatus.Foreground = (SolidColorBrush)FindResource("SuccessBrush");
        }
        catch (Exception ex)
        {
            ExportImportStatus.Text = $"Export failed: {ex.Message}";
            ExportImportStatus.Foreground = (SolidColorBrush)FindResource("ErrorBrush");
        }
    }

    private void ImportBtn_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var (settings, generalSettings) = SettingsExporter.ImportSettings();
            if (settings == null)
            {
                ExportImportStatus.Text = "Import cancelled or invalid file.";
                ExportImportStatus.Foreground = (SolidColorBrush)FindResource("TextSecondaryBrush");
                return;
            }

            Vm.UpdateSettings(settings);
            if (generalSettings != null)
                Vm.UpdateGeneralSettings(generalSettings);

            ExportImportStatus.Text = "Configuration imported successfully.";
            ExportImportStatus.Foreground = (SolidColorBrush)FindResource("SuccessBrush");
        }
        catch (Exception ex)
        {
            ExportImportStatus.Text = $"Import failed: {ex.Message}";
            ExportImportStatus.Foreground = (SolidColorBrush)FindResource("ErrorBrush");
        }
    }

    // Cleanup

    private void ClearChatHistoryBtn_Click(object sender, RoutedEventArgs e)
    {
        if (!Confirm("Clear all chat history?")) return;
        ChatHistoryManager.Instance.DeleteAll();
        CleanupStatus.Text = "Chat history cleared.";
        CleanupStatus.Foreground = (SolidColorBrush)FindResource("SuccessBrush");
    }

    private void ClearReportsBtn_Click(object sender, RoutedEventArgs e)
    {
        if (!Confirm("Clear all stored reports?")) return;
        ReportStorage.Instance.DeleteAll();
        CleanupStatus.Text = "Reports cleared.";
        CleanupStatus.Foreground = (SolidColorBrush)FindResource("SuccessBrush");
    }

    private void ClearTracesBtn_Click(object sender, RoutedEventArgs e)
    {
        if (!Confirm("Clear all API traces?")) return;
        ApiTracer.Instance.ClearTraces();
        CleanupStatus.Text = "Traces cleared.";
        CleanupStatus.Foreground = (SolidColorBrush)FindResource("SuccessBrush");
    }

    private void ClearStatisticsBtn_Click(object sender, RoutedEventArgs e)
    {
        if (!Confirm("Clear all usage statistics?")) return;
        Vm.UsageStats = new Dictionary<string, UsageStats>();
        SettingsPreferences.SaveUsageStats(Vm.UsageStats);
        CleanupStatus.Text = "Statistics cleared.";
        CleanupStatus.Foreground = (SolidColorBrush)FindResource("SuccessBrush");
    }

    private void ClearPromptHistoryBtn_Click(object sender, RoutedEventArgs e)
    {
        if (!Confirm("Clear all prompt history?")) return;
        SettingsPreferences.SavePromptHistory(new List<PromptHistoryEntry>());
        CleanupStatus.Text = "Prompt history cleared.";
        CleanupStatus.Foreground = (SolidColorBrush)FindResource("SuccessBrush");
    }

    private static bool Confirm(string message) =>
        MessageBox.Show(message + " This cannot be undone.", "Confirm",
            MessageBoxButton.YesNo, MessageBoxImage.Warning) == MessageBoxResult.Yes;
}
