using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using AI.Data;
using AI.ViewModels;

namespace AI.Views.Models;

public partial class ModelSearchView : UserControl
{
    private AppViewModel Vm => App.ViewModel;
    private List<string> _allModels = new();

    public ModelSearchView()
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

    private AppService? GetService()
    {
        var idx = ProviderCombo.SelectedIndex;
        return idx >= 0 && idx < AppService.Entries.Count ? AppService.Entries[idx] : null;
    }

    private void ProviderCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        var svc = GetService();
        if (svc == null) return;
        _allModels = Vm.AiSettings.GetModels(svc);
        ApplyFilter();
        EmptyText.Visibility = _allModels.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        StatusText.Text = $"{svc.DisplayName} - {_allModels.Count} models loaded";
    }

    private void SearchBox_TextChanged(object sender, TextChangedEventArgs e) => ApplyFilter();

    private void ApplyFilter()
    {
        var query = SearchBox.Text.Trim().ToLowerInvariant();
        var filtered = string.IsNullOrEmpty(query)
            ? _allModels
            : _allModels.Where(m => m.ToLowerInvariant().Contains(query)).ToList();
        ModelList.ItemsSource = filtered;
        EmptyText.Visibility = filtered.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private async void FetchBtn_Click(object sender, RoutedEventArgs e)
    {
        var svc = GetService();
        if (svc == null) return;
        FetchBtn.IsEnabled = false;
        StatusText.Text = $"Fetching models for {svc.DisplayName}...";
        StatusText.Foreground = (SolidColorBrush)FindResource("TextSecondaryBrush");
        try
        {
            await Vm.FetchModels(svc);
            _allModels = Vm.AiSettings.GetModels(svc);
            ApplyFilter();
            StatusText.Text = $"Loaded {_allModels.Count} models for {svc.DisplayName}";
            StatusText.Foreground = (SolidColorBrush)FindResource("SuccessBrush");
        }
        catch (Exception ex)
        {
            StatusText.Text = $"Error: {ex.Message}";
            StatusText.Foreground = (SolidColorBrush)FindResource("ErrorBrush");
        }
        finally { FetchBtn.IsEnabled = true; }
    }

    private async void TestBtn_Click(object sender, RoutedEventArgs e)
    {
        var svc = GetService();
        if (svc == null || ModelList.SelectedItem is not string model) return;
        var apiKey = Vm.AiSettings.GetApiKey(svc);
        TestBtn.IsEnabled = false;
        StatusText.Text = $"Testing {model}...";
        StatusText.Foreground = (SolidColorBrush)FindResource("TextSecondaryBrush");
        try
        {
            var error = await Vm.TestAiModel(svc, apiKey, model);
            if (error == null)
            {
                StatusText.Text = $"{model} - OK";
                StatusText.Foreground = (SolidColorBrush)FindResource("SuccessBrush");
            }
            else
            {
                StatusText.Text = $"{model} - Error: {error}";
                StatusText.Foreground = (SolidColorBrush)FindResource("ErrorBrush");
            }
        }
        finally { TestBtn.IsEnabled = true; }
    }

    private void ModelList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ModelList.SelectedItem is string model)
            StatusText.Text = $"Selected: {model}";
    }
}
