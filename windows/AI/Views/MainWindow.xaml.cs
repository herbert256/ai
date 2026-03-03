using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using AI.ViewModels;
using AI.Views.Reports;
using AI.Views.Chat;
using AI.Views.Models;
using AI.Views.History;
using AI.Views.Settings;
using AI.Views.Admin;

namespace AI.Views;

public partial class MainWindow : Window
{
    private AppViewModel ViewModel => App.ViewModel;
    private readonly Dictionary<SidebarSection, UserControl> _viewCache = new();

    public MainWindow()
    {
        InitializeComponent();
        BuildSidebar();
        NavigateTo(SidebarSection.Hub);
    }

    private void BuildSidebar()
    {
        var panel = new StackPanel { Margin = new Thickness(8, 0, 8, 8) };

        foreach (var group in Enum.GetValues<SidebarGroup>())
        {
            // Group header
            var header = new TextBlock
            {
                Text = group.ToString().ToUpperInvariant(),
                FontSize = 10,
                FontWeight = FontWeights.SemiBold,
                Foreground = (SolidColorBrush)FindResource("TextSecondaryBrush"),
                Margin = new Thickness(12, 16, 0, 4)
            };
            panel.Children.Add(header);

            foreach (var section in group.SectionsForGroup())
            {
                var item = CreateSidebarItem(section);
                panel.Children.Add(item);
            }
        }

        SidebarItems.ItemsSource = null;
        var container = new ItemsControl();
        container.Items.Add(panel);
        // Replace with direct content
        var sidebarScroll = (ScrollViewer)((DockPanel)((Border)((Grid)Content).Children[0]).Child).Children[1];
        sidebarScroll.Content = panel;
    }

    private Border CreateSidebarItem(SidebarSection section)
    {
        var icon = new TextBlock
        {
            Text = section.Icon(),
            FontFamily = new FontFamily("Segoe MDL2 Assets"),
            FontSize = 14,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 10, 0),
            Foreground = (SolidColorBrush)FindResource("OnSurfaceVariantBrush")
        };

        var label = new TextBlock
        {
            Text = section.DisplayName(),
            FontSize = 13,
            VerticalAlignment = VerticalAlignment.Center,
            Foreground = (SolidColorBrush)FindResource("OnBackgroundBrush")
        };

        var stack = new StackPanel { Orientation = Orientation.Horizontal };
        stack.Children.Add(icon);
        stack.Children.Add(label);

        var border = new Border
        {
            Padding = new Thickness(12, 8, 12, 8),
            CornerRadius = new CornerRadius(6),
            Cursor = Cursors.Hand,
            Tag = section,
            Child = stack
        };

        border.MouseEnter += (s, e) =>
        {
            if (ViewModel.SelectedSection != section)
                border.Background = (SolidColorBrush)FindResource("SidebarHoverBrush");
        };

        border.MouseLeave += (s, e) =>
        {
            if (ViewModel.SelectedSection != section)
                border.Background = Brushes.Transparent;
        };

        border.MouseLeftButtonDown += (s, e) => NavigateTo(section);

        return border;
    }

    private void NavigateTo(SidebarSection section)
    {
        var old = ViewModel.SelectedSection;
        ViewModel.SelectedSection = section;

        // Update sidebar selection visuals
        UpdateSidebarVisuals();

        // Show corresponding view
        if (!_viewCache.TryGetValue(section, out var view))
        {
            view = CreateViewForSection(section);
            _viewCache[section] = view;
        }

        ContentArea.Content = view;
    }

    private void UpdateSidebarVisuals()
    {
        var sidebarScroll = (ScrollViewer)((DockPanel)((Border)((Grid)Content).Children[0]).Child).Children[1];
        if (sidebarScroll.Content is StackPanel panel)
        {
            foreach (var child in panel.Children)
            {
                if (child is Border border && border.Tag is SidebarSection section)
                {
                    border.Background = section == ViewModel.SelectedSection
                        ? (SolidColorBrush)FindResource("SidebarSelectedBrush")
                        : Brushes.Transparent;
                }
            }
        }
    }

    private UserControl CreateViewForSection(SidebarSection section) => section switch
    {
        SidebarSection.Hub => new HubView(),
        SidebarSection.NewReport => new NewReportView(),
        SidebarSection.ReportHistory => new HistoryView(),
        SidebarSection.PromptHistory => new PromptHistoryView(),
        SidebarSection.Chat => new ChatView(),
        SidebarSection.ChatHistory => new HistoryView(),
        SidebarSection.DualChat => new DualChatView(),
        SidebarSection.ModelSearch => new ModelSearchView(),
        SidebarSection.Statistics => new StatisticsView(),
        SidebarSection.Settings => new SettingsView(),
        SidebarSection.Setup => new SetupView(),
        SidebarSection.Housekeeping => new HousekeepingView(),
        SidebarSection.Traces => new TraceView(),
        SidebarSection.Developer => new DeveloperView(),
        SidebarSection.Help => new HelpView(),
        _ => new HubView()
    };
}
