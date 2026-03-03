using System.Windows;
using System.Windows.Controls;
using AI.Data;
using AI.ViewModels;

namespace AI.Views.Settings;

public partial class ServiceSettingsView : UserControl
{
    private AppViewModel Vm => App.ViewModel;

    public ServiceSettingsView()
    {
        InitializeComponent();
        Loaded += (_, _) => ServiceList.ItemsSource = AppService.Entries;
    }

    private void ServiceList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        DetailPanel.Children.Clear();
        if (ServiceList.SelectedItem is not AppService svc) return;

        DetailPanel.Children.Add(new TextBlock
        {
            Text = svc.DisplayName, FontSize = 18, FontWeight = FontWeights.SemiBold,
            Margin = new Thickness(0, 0, 0, 4)
        });
        DetailPanel.Children.Add(new TextBlock
        {
            Text = $"Base URL: {svc.BaseUrl}",
            Foreground = (System.Windows.Media.SolidColorBrush)FindResource("TextSecondaryBrush"),
            FontSize = 12, Margin = new Thickness(0, 0, 0, 4)
        });
        DetailPanel.Children.Add(new TextBlock
        {
            Text = $"Format: {svc.ApiFormatStr}",
            Foreground = (System.Windows.Media.SolidColorBrush)FindResource("TextSecondaryBrush"),
            FontSize = 12, Margin = new Thickness(0, 0, 0, 4)
        });
        DetailPanel.Children.Add(new TextBlock
        {
            Text = $"Default Model: {svc.DefaultModel}",
            Foreground = (System.Windows.Media.SolidColorBrush)FindResource("TextSecondaryBrush"),
            FontSize = 12, Margin = new Thickness(0, 0, 0, 16)
        });

        // Model list URL
        DetailPanel.Children.Add(new TextBlock
        {
            Text = "Model List URL", FontSize = 12,
            Foreground = (System.Windows.Media.SolidColorBrush)FindResource("TextSecondaryBrush"),
            Margin = new Thickness(0, 0, 0, 4)
        });
        var urlBox = new TextBox
        {
            Text = Vm.AiSettings.GetEffectiveModelListUrl(svc),
            Margin = new Thickness(0, 0, 0, 16)
        };
        DetailPanel.Children.Add(urlBox);

        // Endpoints
        DetailPanel.Children.Add(new TextBlock
        {
            Text = "ENDPOINTS", FontSize = 11, FontWeight = FontWeights.SemiBold,
            Foreground = (System.Windows.Media.SolidColorBrush)FindResource("TextSecondaryBrush"),
            Margin = new Thickness(0, 0, 0, 8)
        });

        var endpoints = Vm.AiSettings.GetEndpointsForProvider(svc);
        foreach (var ep in endpoints)
        {
            var border = new Border
            {
                Background = (System.Windows.Media.SolidColorBrush)FindResource("CardBackgroundBrush"),
                BorderBrush = (System.Windows.Media.SolidColorBrush)FindResource("CardBorderBrush"),
                BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(4),
                Padding = new Thickness(12, 8, 12, 8), Margin = new Thickness(0, 0, 0, 4)
            };
            var sp = new StackPanel();
            sp.Children.Add(new TextBlock
            {
                Text = ep.Name + (ep.IsDefault ? " (default)" : ""),
                FontWeight = FontWeights.SemiBold
            });
            sp.Children.Add(new TextBlock
            {
                Text = ep.Url,
                Foreground = (System.Windows.Media.SolidColorBrush)FindResource("TextSecondaryBrush"),
                FontSize = 11
            });
            border.Child = sp;
            DetailPanel.Children.Add(border);
        }
    }
}
