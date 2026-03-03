using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using AI.Data;
using AI.ViewModels;

namespace AI.Views.Settings;

public partial class SetupView : UserControl
{
    private AppViewModel Vm => App.ViewModel;
    private AppService? _selectedService;

    // Per-row controls stored to read back on save
    private TextBox? _apiKeyBox;
    private ComboBox? _modelCombo;

    public SetupView()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        ProviderList.ItemsSource = AppService.Entries;
    }

    private void ProviderList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        _selectedService = ProviderList.SelectedItem as AppService;
        BuildDetailPanel();
    }

    private void BuildDetailPanel()
    {
        DetailPanel.Children.Clear();
        var svc = _selectedService;
        if (svc == null) return;

        var config = Vm.AiSettings.GetProvider(svc);
        var state = Vm.AiSettings.GetProviderState(svc);

        // Title
        DetailPanel.Children.Add(new TextBlock
        {
            Text = svc.DisplayName,
            FontSize = 20,
            FontWeight = FontWeights.SemiBold,
            Margin = new Thickness(0, 0, 0, 4)
        });

        // Status
        var stateColor = state == "ok" ? "SuccessBrush" : state == "inactive" ? "TextSecondaryBrush" : "WarningBrush";
        DetailPanel.Children.Add(new TextBlock
        {
            Text = $"Status: {state}",
            Foreground = (SolidColorBrush)FindResource(stateColor),
            FontSize = 12,
            Margin = new Thickness(0, 0, 0, 16)
        });

        // API Key
        DetailPanel.Children.Add(MakeLabel("API Key"));
        _apiKeyBox = new TextBox { Text = config.ApiKey, Margin = new Thickness(0, 0, 0, 12) };
        DetailPanel.Children.Add(_apiKeyBox);

        // Model
        DetailPanel.Children.Add(MakeLabel("Model"));
        var models = config.Models.Count > 0 ? config.Models : (svc.HardcodedModels ?? new());
        _modelCombo = new ComboBox
        {
            IsEditable = true,
            ItemsSource = models.Count > 0 ? models : new List<string> { svc.DefaultModel },
            Text = !string.IsNullOrEmpty(config.Model) ? config.Model : svc.DefaultModel,
            Margin = new Thickness(0, 0, 0, 16)
        };
        DetailPanel.Children.Add(_modelCombo);

        // Buttons
        var btnPanel = new StackPanel { Orientation = Orientation.Horizontal };

        var saveBtn = new Button { Content = "Save", Width = 80, Margin = new Thickness(0, 0, 8, 0) };
        saveBtn.Click += SaveBtn_Click;
        btnPanel.Children.Add(saveBtn);

        var fetchBtn = new Button { Content = "Fetch Models", Style = (Style)FindResource("SecondaryButton"), Margin = new Thickness(0, 0, 8, 0) };
        fetchBtn.Click += async (s, e) =>
        {
            fetchBtn.IsEnabled = false;
            await Vm.FetchModels(svc);
            var updated = Vm.AiSettings.GetModels(svc);
            if (updated.Count > 0)
                _modelCombo.ItemsSource = updated;
            fetchBtn.IsEnabled = true;
        };
        btnPanel.Children.Add(fetchBtn);

        var testBtn = new Button { Content = "Test", Style = (Style)FindResource("SecondaryButton") };
        testBtn.Click += async (s, e) =>
        {
            testBtn.IsEnabled = false;
            var err = await Vm.TestAiModel(svc, _apiKeyBox.Text.Trim(), _modelCombo.Text.Trim());
            MessageBox.Show(err ?? "Connection successful!", "Test Result",
                MessageBoxButton.OK, err == null ? MessageBoxImage.Information : MessageBoxImage.Warning);
            testBtn.IsEnabled = true;
        };
        btnPanel.Children.Add(testBtn);

        DetailPanel.Children.Add(btnPanel);
    }

    private void SaveBtn_Click(object sender, RoutedEventArgs e)
    {
        var svc = _selectedService;
        if (svc == null || _apiKeyBox == null || _modelCombo == null) return;
        var config = Vm.AiSettings.GetProvider(svc);
        config.ApiKey = _apiKeyBox.Text.Trim();
        config.Model = _modelCombo.Text.Trim();
        Vm.AiSettings.SetProvider(svc, config);
        Vm.UpdateSettings(Vm.AiSettings);

        var state = string.IsNullOrEmpty(config.ApiKey) ? "not-used" : "ok";
        Vm.UpdateProviderState(svc, state);
        BuildDetailPanel();
    }

    private static TextBlock MakeLabel(string text) =>
        new() { Text = text, Foreground = null, FontSize = 12, Margin = new Thickness(0, 0, 0, 4) };
}
