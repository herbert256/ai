using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using AI.Data;
using AI.ViewModels;

namespace AI.Views.Settings;

public partial class EndpointsView : UserControl
{
    private AppViewModel Vm => App.ViewModel;

    public EndpointsView()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        BuildEndpointsList();
    }

    private void BuildEndpointsList()
    {
        EndpointsPanel.Children.Clear();
        var totalCount = 0;

        foreach (var service in AppService.Entries)
        {
            var endpoints = Vm.AiSettings.GetEndpointsForProvider(service);
            if (endpoints.Count == 0) continue;

            totalCount += endpoints.Count;

            var expander = new Expander
            {
                Header = new TextBlock
                {
                    Text = $"{service.DisplayName} ({endpoints.Count})",
                    FontWeight = FontWeights.SemiBold,
                    FontSize = 14
                },
                IsExpanded = false,
                Margin = new Thickness(0, 0, 0, 8)
            };

            var listPanel = new StackPanel { Margin = new Thickness(8, 4, 0, 4) };

            foreach (var ep in endpoints)
            {
                var row = new Border
                {
                    Background = (Brush)FindResource("CardBackgroundBrush"),
                    BorderBrush = (Brush)FindResource("CardBorderBrush"),
                    BorderThickness = new Thickness(1),
                    CornerRadius = new CornerRadius(4),
                    Padding = new Thickness(12, 8, 12, 8),
                    Margin = new Thickness(0, 0, 0, 4)
                };

                var content = new StackPanel();

                var nameRow = new StackPanel { Orientation = Orientation.Horizontal };
                nameRow.Children.Add(new TextBlock
                {
                    Text = ep.Name,
                    FontWeight = FontWeights.SemiBold,
                    VerticalAlignment = VerticalAlignment.Center
                });

                if (ep.IsDefault)
                {
                    var badge = new Border
                    {
                        Background = (Brush)FindResource("PrimaryBrush"),
                        CornerRadius = new CornerRadius(3),
                        Padding = new Thickness(6, 1, 6, 1),
                        Margin = new Thickness(8, 0, 0, 0),
                        VerticalAlignment = VerticalAlignment.Center
                    };
                    badge.Child = new TextBlock
                    {
                        Text = "DEFAULT",
                        FontSize = 10,
                        FontWeight = FontWeights.SemiBold,
                        Foreground = Brushes.White
                    };
                    nameRow.Children.Add(badge);
                }

                content.Children.Add(nameRow);
                content.Children.Add(new TextBlock
                {
                    Text = ep.Url,
                    FontSize = 11,
                    Foreground = (Brush)FindResource("TextSecondaryBrush"),
                    TextWrapping = TextWrapping.Wrap,
                    Margin = new Thickness(0, 2, 0, 0)
                });

                row.Child = content;
                listPanel.Children.Add(row);
            }

            expander.Content = listPanel;
            EndpointsPanel.Children.Add(expander);
        }

        CountLabel.Text = $"{totalCount} endpoint{(totalCount == 1 ? "" : "s")}";
    }
}
