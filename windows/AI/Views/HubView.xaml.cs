using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using AI.Data;
using AI.ViewModels;

namespace AI.Views;

public partial class HubView : UserControl
{
    private AppViewModel Vm => App.ViewModel;

    public HubView()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        var name = Vm.GeneralSettings.UserName;
        GreetingText.Text = $"Welcome back, {name}";

        var totalCalls = Vm.UsageStats.Values.Sum(u => u.CallCount);
        var stats = new[]
        {
            ("Providers", AppService.Entries.Count.ToString(), "AI services available"),
            ("Agents", Vm.AiSettings.Agents.Count.ToString(), "configured agents"),
            ("Flocks", Vm.AiSettings.Flocks.Count.ToString(), "agent groups"),
            ("Swarms", Vm.AiSettings.Swarms.Count.ToString(), "model swarms"),
            ("API Calls", totalCalls.ToString("N0"), "total across all providers"),
        };

        StatsPanel.Children.Clear();
        foreach (var (label, value, subtitle) in stats)
            StatsPanel.Children.Add(MakeCard(label, value, subtitle));
    }

    private Border MakeCard(string label, string value, string subtitle)
    {
        var stack = new StackPanel { Margin = new Thickness(16) };
        stack.Children.Add(new TextBlock
        {
            Text = label,
            Foreground = (SolidColorBrush)FindResource("TextSecondaryBrush"),
            FontSize = 11,
            FontWeight = FontWeights.SemiBold
        });
        stack.Children.Add(new TextBlock
        {
            Text = value,
            FontSize = 32,
            FontWeight = FontWeights.Bold,
            Foreground = (SolidColorBrush)FindResource("PrimaryBrush"),
            Margin = new Thickness(0, 4, 0, 2)
        });
        stack.Children.Add(new TextBlock
        {
            Text = subtitle,
            Foreground = (SolidColorBrush)FindResource("TextSecondaryBrush"),
            FontSize = 11
        });

        return new Border
        {
            Width = 160,
            Background = (SolidColorBrush)FindResource("CardBackgroundBrush"),
            BorderBrush = (SolidColorBrush)FindResource("CardBorderBrush"),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(8),
            Margin = new Thickness(0, 0, 12, 12),
            Child = stack
        };
    }

    private void NewReportBtn_Click(object sender, RoutedEventArgs e) =>
        Vm.SelectedSection = SidebarSection.NewReport;

    private void ChatBtn_Click(object sender, RoutedEventArgs e) =>
        Vm.SelectedSection = SidebarSection.Chat;

    private void SetupBtn_Click(object sender, RoutedEventArgs e) =>
        Vm.SelectedSection = SidebarSection.Setup;
}
