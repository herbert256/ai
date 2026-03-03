using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using AI.Data;
using AI.Helpers;
using AI.ViewModels;

namespace AI.Views.Admin;

public partial class StatisticsView : UserControl
{
    private AppViewModel Vm => App.ViewModel;

    public StatisticsView()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e) => Refresh();

    private void Refresh()
    {
        var stats = Vm.UsageStats;

        // Calculate totals
        double totalCost = 0;
        long totalCalls = 0;
        long totalInput = 0;
        long totalOutput = 0;
        var modelsUsed = new HashSet<string>();

        foreach (var (_, usage) in stats)
        {
            totalCalls += usage.CallCount;
            totalInput += usage.InputTokens;
            totalOutput += usage.OutputTokens;
            modelsUsed.Add(usage.Model);
            totalCost += CalculateCost(usage);
        }

        // Summary cards
        SummaryPanel.Children.Clear();
        var summaryItems = new[]
        {
            ("Total Cost", UiFormatting.FormatUsd(totalCost, 4), "across all providers"),
            ("API Calls", UiFormatting.FormatCompactNumber(totalCalls), "total requests"),
            ("Input Tokens", UiFormatting.FormatCompactNumber(totalInput), "prompt tokens"),
            ("Output Tokens", UiFormatting.FormatCompactNumber(totalOutput), "completion tokens"),
            ("Models Used", modelsUsed.Count.ToString(), "distinct models"),
        };

        foreach (var (label, value, subtitle) in summaryItems)
            SummaryPanel.Children.Add(MakeSummaryCard(label, value, subtitle));

        // Group by provider, sorted by cost descending
        var providerGroups = stats.Values
            .GroupBy(u => u.ProviderId)
            .Select(g =>
            {
                var providerCost = g.Sum(u => CalculateCost(u));
                var providerCalls = g.Sum(u => (long)u.CallCount);
                return new { ProviderId = g.Key, Cost = providerCost, Calls = providerCalls, Models = g.ToList() };
            })
            .OrderByDescending(g => g.Cost)
            .ToList();

        ProvidersPanel.Children.Clear();
        foreach (var group in providerGroups)
        {
            var service = AppService.FindById(group.ProviderId);
            var providerName = service?.DisplayName ?? group.ProviderId;

            var expander = new Expander
            {
                IsExpanded = false,
                Margin = new Thickness(0, 0, 0, 8),
                Padding = new Thickness(12)
            };

            // Header
            var headerPanel = new StackPanel { Orientation = Orientation.Horizontal };
            headerPanel.Children.Add(new TextBlock
            {
                Text = providerName,
                FontWeight = FontWeights.SemiBold,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 0, 16, 0)
            });
            headerPanel.Children.Add(new TextBlock
            {
                Text = UiFormatting.FormatUsd(group.Cost, 4),
                Foreground = (SolidColorBrush)FindResource("PrimaryBrush"),
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 0, 16, 0)
            });
            headerPanel.Children.Add(new TextBlock
            {
                Text = $"{group.Calls:N0} calls",
                Foreground = (SolidColorBrush)FindResource("TextSecondaryBrush"),
                VerticalAlignment = VerticalAlignment.Center
            });
            expander.Header = headerPanel;

            // Content: model list
            var modelList = new StackPanel();
            var sortedModels = group.Models.OrderByDescending(u => CalculateCost(u)).ToList();

            foreach (var usage in sortedModels)
            {
                var cost = CalculateCost(usage);
                var row = new Border
                {
                    Background = (SolidColorBrush)FindResource("CardBackgroundBrush"),
                    BorderBrush = (SolidColorBrush)FindResource("CardBorderBrush"),
                    BorderThickness = new Thickness(1),
                    CornerRadius = new CornerRadius(4),
                    Padding = new Thickness(12, 8, 12, 8),
                    Margin = new Thickness(0, 0, 0, 4)
                };

                var rowGrid = new Grid();
                rowGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                rowGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                rowGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                rowGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                rowGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

                var modelName = new TextBlock
                {
                    Text = usage.Model,
                    TextTrimming = TextTrimming.CharacterEllipsis,
                    VerticalAlignment = VerticalAlignment.Center
                };
                Grid.SetColumn(modelName, 0);
                rowGrid.Children.Add(modelName);

                var callsText = new TextBlock
                {
                    Text = $"{usage.CallCount:N0} calls",
                    Foreground = (SolidColorBrush)FindResource("TextSecondaryBrush"),
                    FontSize = 11,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(12, 0, 0, 0)
                };
                Grid.SetColumn(callsText, 1);
                rowGrid.Children.Add(callsText);

                var inputText = new TextBlock
                {
                    Text = $"In: {UiFormatting.FormatCompactNumber(usage.InputTokens)}",
                    Foreground = (SolidColorBrush)FindResource("TextSecondaryBrush"),
                    FontSize = 11,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(12, 0, 0, 0)
                };
                Grid.SetColumn(inputText, 2);
                rowGrid.Children.Add(inputText);

                var outputText = new TextBlock
                {
                    Text = $"Out: {UiFormatting.FormatCompactNumber(usage.OutputTokens)}",
                    Foreground = (SolidColorBrush)FindResource("TextSecondaryBrush"),
                    FontSize = 11,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(12, 0, 0, 0)
                };
                Grid.SetColumn(outputText, 3);
                rowGrid.Children.Add(outputText);

                var costText = new TextBlock
                {
                    Text = UiFormatting.FormatUsd(cost, 6),
                    Foreground = (SolidColorBrush)FindResource("PrimaryBrush"),
                    FontSize = 11,
                    FontWeight = FontWeights.SemiBold,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(12, 0, 0, 0),
                    MinWidth = 80,
                    TextAlignment = TextAlignment.Right
                };
                Grid.SetColumn(costText, 4);
                rowGrid.Children.Add(costText);

                row.Child = rowGrid;
                modelList.Children.Add(row);
            }

            expander.Content = modelList;
            ProvidersPanel.Children.Add(expander);
        }

        if (providerGroups.Count == 0)
        {
            ProvidersPanel.Children.Add(new TextBlock
            {
                Text = "No usage data yet. Use AI features to see statistics here.",
                Foreground = (SolidColorBrush)FindResource("TextSecondaryBrush"),
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 40, 0, 0)
            });
        }
    }

    private static double CalculateCost(UsageStats usage)
    {
        var service = AppService.FindById(usage.ProviderId);
        if (service == null) return 0;
        var pricing = PricingCache.Instance.GetPricing(service, usage.Model);
        return (usage.InputTokens * pricing.PromptPrice + usage.OutputTokens * pricing.CompletionPrice);
    }

    private Border MakeSummaryCard(string label, string value, string subtitle)
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
            FontSize = 28,
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

    private void ClearBtn_Click(object sender, RoutedEventArgs e)
    {
        var result = MessageBox.Show("Clear all usage statistics? This cannot be undone.",
            "Clear Statistics", MessageBoxButton.YesNo, MessageBoxImage.Warning);
        if (result != MessageBoxResult.Yes) return;

        Vm.UsageStats = new Dictionary<string, UsageStats>();
        SettingsPreferences.SaveUsageStats(Vm.UsageStats);
        Refresh();
    }
}
